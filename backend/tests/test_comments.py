"""Comment endpoint tests."""

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
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    return alice, bob, alice_token, bob_token, rel


def test_create_and_list_expense_comments(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )

    # Bob comments
    response = client.post(
        f"/api/v1/expenses/{expense['id']}/comments",
        json={"content": "Is this for lunch?"},
        headers=auth_headers(bob_token),
    )
    assert response.status_code == 201
    assert response.get_json()["content"] == "Is this for lunch?"

    # Alice lists comments
    list_res = client.get(
        f"/api/v1/expenses/{expense['id']}/comments",
        headers=auth_headers(alice_token),
    )
    assert list_res.status_code == 200
    items = list_res.get_json()["items"]
    assert len(items) == 1
    assert items[0]["content"] == "Is this for lunch?"
    assert items[0]["user_id"] == bob["id"]


def test_create_and_list_payment_comments(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    # Alice comments
    client.post(
        f"/api/v1/payments/{payment['id']}/comments",
        json={"content": "Here is the money"},
        headers=auth_headers(alice_token),
    )

    list_res = client.get(
        f"/api/v1/payments/{payment['id']}/comments",
        headers=auth_headers(bob_token),
    )
    assert list_res.status_code == 200
    items = list_res.get_json()["items"]
    assert len(items) == 1
    assert items[0]["content"] == "Here is the money"
    assert items[0]["user_id"] == alice["id"]
