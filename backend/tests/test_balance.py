"""Balance computation tests."""

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


def _balance(client: FlaskClient, token: str, rel_id: int):
    return client.get(
        f"/api/v1/relationships/{rel_id}/balance",
        headers=auth_headers(token),
    )


def test_balance_against_pending_relationship_returns_409(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    response = _balance(client, alice_token, rel["id"])
    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_not_accepted"


def test_single_confirmed_expense_shifts_balance(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    # Alice pays $100, split evenly. Bob owes Alice $50.
    make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=10000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 5000},
            {"user_id": bob["id"], "amount_cents": 5000},
        ],
        confirm_token=bob_token,
    )

    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["relationship_id"] == rel["id"]
    assert body["confirmed"] == {
        "net_cents": 5000,
        "from_user_id": bob["id"],
        "to_user_id": alice["id"],
    }
    assert body["projected"] == body["confirmed"]


def test_confirmed_payment_reduces_debt_then_overpayment_flips(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    # Bob owes Alice $50 after expense.
    make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=10000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 5000},
            {"user_id": bob["id"], "amount_cents": 5000},
        ],
        confirm_token=bob_token,
    )

    # Bob pays Alice $30; debt drops to $20.
    make_payment(
        client,
        bob_token,
        relationship_id=rel["id"],
        from_user_id=bob["id"],
        to_user_id=alice["id"],
        amount_cents=3000,
        confirm_token=alice_token,
    )
    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["confirmed"] == {
        "net_cents": 2000,
        "from_user_id": bob["id"],
        "to_user_id": alice["id"],
    }

    # Bob over-pays another $50; now Alice owes Bob $30.
    make_payment(
        client,
        bob_token,
        relationship_id=rel["id"],
        from_user_id=bob["id"],
        to_user_id=alice["id"],
        amount_cents=5000,
        confirm_token=alice_token,
    )
    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["confirmed"] == {
        "net_cents": 3000,
        "from_user_id": alice["id"],
        "to_user_id": bob["id"],
    }


def test_pending_expense_appears_in_projected_only(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=4000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2000},
            {"user_id": bob["id"], "amount_cents": 2000},
        ],
    )

    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["confirmed"] == {
        "net_cents": 0,
        "from_user_id": None,
        "to_user_id": None,
    }
    assert body["projected"] == {
        "net_cents": 2000,
        "from_user_id": bob["id"],
        "to_user_id": alice["id"],
    }


def test_pending_payment_appears_in_projected_only(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    # Confirmed expense: Bob owes Alice $50.
    make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=10000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 5000},
            {"user_id": bob["id"], "amount_cents": 5000},
        ],
        confirm_token=bob_token,
    )
    # Bob's pending payment of $20: projected reduces Bob's debt, confirmed does not.
    make_payment(
        client,
        bob_token,
        relationship_id=rel["id"],
        from_user_id=bob["id"],
        to_user_id=alice["id"],
        amount_cents=2000,
    )

    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["confirmed"] == {
        "net_cents": 5000,
        "from_user_id": bob["id"],
        "to_user_id": alice["id"],
    }
    assert body["projected"] == {
        "net_cents": 3000,
        "from_user_id": bob["id"],
        "to_user_id": alice["id"],
    }


def test_confirmed_reversal_returns_balance_to_prior(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    original = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=4000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2000},
            {"user_id": bob["id"], "amount_cents": 2000},
        ],
        confirm_token=bob_token,
    )

    reversal = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    ).get_json()
    client.post(
        f"/api/v1/expenses/{reversal['id']}/confirm",
        headers=auth_headers(alice_token),
    )

    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["confirmed"] == {
        "net_cents": 0,
        "from_user_id": None,
        "to_user_id": None,
    }
    assert body["projected"] == body["confirmed"]


def test_pending_reversal_affects_projected_only(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    original = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=4000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2000},
            {"user_id": bob["id"], "amount_cents": 2000},
        ],
        confirm_token=bob_token,
    )

    # Pending reversal — not confirmed.
    client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["confirmed"] == {
        "net_cents": 2000,
        "from_user_id": bob["id"],
        "to_user_id": alice["id"],
    }
    assert body["projected"] == {
        "net_cents": 0,
        "from_user_id": None,
        "to_user_id": None,
    }


def test_zero_balance_returns_null_party_ids(client: FlaskClient) -> None:
    _, _, alice_token, _, rel = _two_party_setup(client)

    body = _balance(client, alice_token, rel["id"]).get_json()
    assert body["confirmed"] == {
        "net_cents": 0,
        "from_user_id": None,
        "to_user_id": None,
    }
    assert body["projected"] == body["confirmed"]
