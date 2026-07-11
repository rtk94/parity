"""Account data-export and deletion (anonymization) tests."""

from __future__ import annotations

from flask.testing import FlaskClient

from tests.factories import (
    auth_headers,
    make_expense,
    make_logged_in_user,
    make_payment,
    make_relationship,
    make_user,
)


def _seed_ledger(client: FlaskClient):
    """Alice and Bob with one confirmed expense, payment, and a comment.

    Returns ``(alice, alice_token, bob, bob_token, relationship)``.
    """
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob")
    make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
        confirm_token=bob_token,
    )
    payment = make_payment(
        client,
        bob_token,
        relationship_id=rel["id"],
        from_user_id=bob["id"],
        to_user_id=alice["id"],
        amount_cents=500,
        confirm_token=alice_token,
    )
    client.post(
        f"/api/v1/payments/{payment['id']}/comments",
        json={"content": "Paid you back for lunch"},
        headers=auth_headers(bob_token),
    )
    return alice, alice_token, bob, bob_token, rel


def test_export_returns_full_account_dump(client: FlaskClient) -> None:
    alice, alice_token, bob, _bob_token, rel = _seed_ledger(client)

    resp = client.get("/api/v1/auth/me/export", headers=auth_headers(alice_token))
    assert resp.status_code == 200, resp.get_json()
    body = resp.get_json()

    assert body["user"]["id"] == alice["id"]
    assert body["user"]["username"] == "alice"
    assert body["exported_at"].endswith("Z")
    assert len(body["relationships"]) == 1
    assert body["relationships"][0]["id"] == rel["id"]
    assert len(body["expenses"]) == 1
    assert len(body["payments"]) == 1
    # Alice authored no comments; only Bob did.
    assert body["comments"] == []


def test_export_includes_own_comments(client: FlaskClient) -> None:
    _alice, _alice_token, _bob, bob_token, _rel = _seed_ledger(client)

    resp = client.get("/api/v1/auth/me/export", headers=auth_headers(bob_token))
    assert resp.status_code == 200, resp.get_json()
    comments = resp.get_json()["comments"]
    assert len(comments) == 1
    assert comments[0]["content"] == "Paid you back for lunch"


def test_export_requires_auth(client: FlaskClient) -> None:
    resp = client.get("/api/v1/auth/me/export")
    assert resp.status_code == 401


def test_delete_account_anonymizes_and_revokes(client: FlaskClient) -> None:
    _alice, alice_token, _bob, bob_token, rel = _seed_ledger(client)

    resp = client.delete(
        "/api/v1/auth/me",
        json={"password": "pw-alice"},
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 204

    # The old token no longer authenticates.
    me = client.get("/api/v1/auth/me", headers=auth_headers(alice_token))
    assert me.status_code == 401

    # Login is refused for the deleted account.
    login = client.post("/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"})
    assert login.status_code == 401

    # Bob's shared ledger survives, and Alice shows as anonymized to him.
    rels = client.get("/api/v1/relationships", headers=auth_headers(bob_token))
    assert rels.status_code == 200
    parties = [rels.get_json()["items"][0][k] for k in ("inviting_user", "invited_user")]
    alice_view = next(p for p in parties if p["id"] == _alice["id"])
    assert alice_view["display_name"] == "Deleted user"
    assert alice_view["username"] == f"deleted_user_{_alice['id']}"

    # Bob can still read the shared expenses.
    expenses = client.get(
        f"/api/v1/expenses?relationship_id={rel['id']}",
        headers=auth_headers(bob_token),
    )
    assert expenses.status_code == 200
    assert len(expenses.get_json()["items"]) == 1


def test_delete_account_requires_correct_password(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "carol")

    wrong = client.delete(
        "/api/v1/auth/me",
        json={"password": "not-my-password"},
        headers=auth_headers(token),
    )
    assert wrong.status_code == 403
    assert wrong.get_json()["error"]["code"] == "invalid_password"

    # Account is untouched: the token still works.
    assert client.get("/api/v1/auth/me", headers=auth_headers(token)).status_code == 200


def test_delete_account_requires_password_field(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "dave")

    resp = client.delete("/api/v1/auth/me", json={}, headers=auth_headers(token))
    assert resp.status_code == 403
    assert resp.get_json()["error"]["code"] == "invalid_password"


def test_deleted_username_can_be_reused(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "erin")
    resp = client.delete(
        "/api/v1/auth/me",
        json={"password": "pw-erin"},
        headers=auth_headers(token),
    )
    assert resp.status_code == 204

    # The freed username registers cleanly (the old row was renamed away).
    reused = make_user(client, "erin")
    assert reused["username"] == "erin"
