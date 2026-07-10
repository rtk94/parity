"""Pending-confirmation endpoint tests (`GET /api/v1/pending`)."""

from __future__ import annotations

from typing import Any

from flask.testing import FlaskClient

from tests.factories import (
    auth_headers,
    make_expense,
    make_logged_in_user,
    make_payment,
    make_relationship,
)


def _two_party_setup(
    client: FlaskClient,
) -> tuple[dict[str, Any], dict[str, Any], str, str, dict[str, Any]]:
    """Alice and Bob with an accepted relationship.

    Returns (alice, bob, alice_token, bob_token, relationship).
    """
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    return alice, bob, alice_token, bob_token, rel


def _expense(client: FlaskClient, token: str, alice: dict, bob: dict, rel: dict, **kw: Any):
    return make_expense(
        client,
        token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=5000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2500},
            {"user_id": bob["id"], "amount_cents": 2500},
        ],
        **kw,
    )


def test_pending_lists_entries_awaiting_the_caller(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    expense = _expense(client, alice_token, alice, bob, rel)

    resp = client.get("/api/v1/pending", headers=auth_headers(bob_token))

    assert resp.status_code == 200, resp.get_json()
    body = resp.get_json()
    assert [e["id"] for e in body["expenses"]] == [expense["id"]]
    assert body["payments"] == []


def test_pending_excludes_entries_the_caller_created(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    _expense(client, alice_token, alice, bob, rel)

    resp = client.get("/api/v1/pending", headers=auth_headers(alice_token))

    body = resp.get_json()
    assert body["expenses"] == []
    assert body["payments"] == []


def test_pending_includes_payments(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=3000,
    )

    resp = client.get("/api/v1/pending", headers=auth_headers(bob_token))

    body = resp.get_json()
    assert [p["id"] for p in body["payments"]] == [payment["id"]]
    assert body["expenses"] == []


def test_pending_excludes_confirmed_entries(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    _expense(client, alice_token, alice, bob, rel, confirm_token=bob_token)

    resp = client.get("/api/v1/pending", headers=auth_headers(bob_token))

    body = resp.get_json()
    assert body["expenses"] == []
    assert body["payments"] == []


def test_pending_excludes_discarded_entries(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    expense = _expense(client, alice_token, alice, bob, rel)

    discard = client.post(
        f"/api/v1/expenses/{expense['id']}/discard",
        headers=auth_headers(bob_token),
    )
    assert discard.status_code == 200, discard.get_json()

    resp = client.get("/api/v1/pending", headers=auth_headers(bob_token))
    body = resp.get_json()
    assert body["expenses"] == []


def test_pending_does_not_leak_other_pairs(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    _expense(client, alice_token, alice, bob, rel)
    _, carol_token = make_logged_in_user(client, "carol")

    resp = client.get("/api/v1/pending", headers=auth_headers(carol_token))

    body = resp.get_json()
    assert body["expenses"] == []
    assert body["payments"] == []


def test_pending_requires_auth(client: FlaskClient) -> None:
    resp = client.get("/api/v1/pending")
    assert resp.status_code == 401
