"""Expense endpoint tests."""

from __future__ import annotations

from typing import Any

from flask.testing import FlaskClient

from tests.factories import (
    auth_headers,
    make_expense,
    make_logged_in_user,
    make_relationship,
    make_user,
)


def _two_party_setup(
    client: FlaskClient,
) -> tuple[dict[str, Any], dict[str, Any], str, str, dict[str, Any]]:
    """Set up Alice and Bob with an accepted relationship.

    Returns (alice_user, bob_user, alice_token, bob_token, relationship).
    """
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    return alice, bob, alice_token, bob_token, rel


def test_create_expense_with_valid_shares_succeeds(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 5000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 2500},
                {"user_id": bob["id"], "amount_cents": 2500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 201
    body = response.get_json()
    assert body["relationship_id"] == rel["id"]
    assert body["payer_user_id"] == alice["id"]
    assert body["total_cents"] == 5000
    assert body["description"] == "Lunch"
    assert body["created_by_user_id"] == alice["id"]
    assert body["status"] == "pending"
    assert body["confirmed_at"] is None
    assert body["confirmed_by_user_id"] is None
    assert body["discarded_at"] is None
    assert body["discarded_by_user_id"] is None
    assert body["rejection_reason"] is None
    assert body["reverses_expense_id"] is None
    assert body["created_at"].endswith("Z")
    share_pairs = {(s["user_id"], s["amount_cents"]) for s in body["shares"]}
    assert share_pairs == {(alice["id"], 2500), (bob["id"], 2500)}


def test_create_share_sum_mismatch_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 5000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 2400},
                {"user_id": bob["id"], "amount_cents": 2500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    body = response.get_json()["error"]
    assert body["code"] == "share_sum_mismatch"
    assert body["details"] == {"total_cents": 5000, "shares_sum_cents": 4900}


def test_create_duplicate_share_user_returns_422(client: FlaskClient) -> None:
    alice, _, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 5000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 2500},
                {"user_id": alice["id"], "amount_cents": 2500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "duplicate_share_user"


def test_create_non_party_share_user_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    carol = make_user(client, "carol")

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 5000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 2500},
                {"user_id": carol["id"], "amount_cents": 2500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "invalid_share_user"


def test_create_non_party_payer_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    carol = make_user(client, "carol")

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": carol["id"],
            "total_cents": 5000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 2500},
                {"user_id": bob["id"], "amount_cents": 2500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "invalid_payer"


def test_create_against_pending_relationship_returns_409(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob = make_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 500},
                {"user_id": bob["id"], "amount_cents": 500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_not_accepted"


def test_create_against_rejected_relationship_returns_409(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)
    client.post(
        f"/api/v1/relationships/{rel['id']}/reject",
        headers=auth_headers(bob_token),
    )

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 500},
                {"user_id": bob["id"], "amount_cents": 500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_not_accepted"


def test_create_empty_shares_returns_422(client: FlaskClient) -> None:
    alice, _, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Lunch",
            "shares": [],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "empty_shares"


def test_create_empty_description_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "   ",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 500},
                {"user_id": bob["id"], "amount_cents": 500},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "empty_description"


def test_create_zero_total_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 0,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 0},
                {"user_id": bob["id"], "amount_cents": 0},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "invalid_amount"


def test_create_negative_share_amount_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 100,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": -50},
                {"user_id": bob["id"], "amount_cents": 150},
            ],
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "invalid_amount"


def test_confirm_by_counterparty_succeeds(client: FlaskClient) -> None:
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

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/confirm",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 200
    body = response.get_json()
    assert body["status"] == "confirmed"
    assert body["confirmed_by_user_id"] == bob["id"]
    assert body["confirmed_at"] is not None and body["confirmed_at"].endswith("Z")


def test_confirm_by_creator_returns_403(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
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

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/confirm",
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 403
    assert response.get_json()["error"]["code"] == "cannot_self_confirm"


def test_confirm_of_non_pending_returns_409(client: FlaskClient) -> None:
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
        confirm_token=bob_token,
    )

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/confirm",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "expense_not_pending"


def test_discard_by_creator_sets_discarded_by(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
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

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/discard",
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 200
    body = response.get_json()
    assert body["status"] == "discarded"
    assert body["discarded_by_user_id"] == alice["id"]
    assert body["discarded_at"].endswith("Z")
    assert body["rejection_reason"] is None


def test_discard_by_counterparty_sets_discarded_by(client: FlaskClient) -> None:
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

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/discard",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 200
    body = response.get_json()
    assert body["status"] == "discarded"
    assert body["discarded_by_user_id"] == bob["id"]


def test_discard_with_reason_stores_reason(client: FlaskClient) -> None:
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

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/discard",
        json={"reason": "Never went to that lunch."},
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 200
    assert response.get_json()["rejection_reason"] == "Never went to that lunch."


def test_discard_of_non_pending_returns_409(client: FlaskClient) -> None:
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
        confirm_token=bob_token,
    )

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/discard",
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "expense_not_pending"


def test_reverse_of_confirmed_creates_pending_reversal_with_mirrored_shares(
    client: FlaskClient,
) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    original = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 600},
            {"user_id": bob["id"], "amount_cents": 400},
        ],
        confirm_token=bob_token,
    )

    response = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 201
    body = response.get_json()
    assert body["status"] == "pending"
    assert body["reverses_expense_id"] == original["id"]
    assert body["payer_user_id"] == alice["id"]
    assert body["total_cents"] == 1000
    assert body["description"] == f"Reversal of expense #{original['id']}"
    share_pairs = {(s["user_id"], s["amount_cents"]) for s in body["shares"]}
    assert share_pairs == {(alice["id"], 600), (bob["id"], 400)}


def test_reverse_of_pending_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    pending = make_expense(
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

    response = client.post(
        f"/api/v1/expenses/{pending['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "original_not_confirmed"


def test_reverse_of_discarded_returns_409(client: FlaskClient) -> None:
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
    client.post(
        f"/api/v1/expenses/{expense['id']}/discard",
        headers=auth_headers(alice_token),
    )

    response = client.post(
        f"/api/v1/expenses/{expense['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "original_not_confirmed"


def test_reverse_with_existing_pending_reversal_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    original = make_expense(
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

    client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    response = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "already_reversed"


def test_reverse_with_existing_confirmed_reversal_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    original = make_expense(
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

    reversal = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    ).get_json()
    # Counterparty to the reversal is Alice (because Bob created it).
    client.post(
        f"/api/v1/expenses/{reversal['id']}/confirm",
        headers=auth_headers(alice_token),
    )

    response = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "already_reversed"


def test_reverse_after_discarded_reversal_succeeds(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    original = make_expense(
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

    first_reversal = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    ).get_json()
    client.post(
        f"/api/v1/expenses/{first_reversal['id']}/discard",
        headers=auth_headers(bob_token),
    )

    second_reversal = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    )
    assert second_reversal.status_code == 201


def test_reverse_of_reversal_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    original = make_expense(
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
    reversal = client.post(
        f"/api/v1/expenses/{original['id']}/reverse",
        headers=auth_headers(bob_token),
    ).get_json()
    client.post(
        f"/api/v1/expenses/{reversal['id']}/confirm",
        headers=auth_headers(alice_token),
    )

    response = client.post(
        f"/api/v1/expenses/{reversal['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "original_is_reversal"


def test_list_filters_and_scopes_to_party(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel_ab = _two_party_setup(client)
    carol, carol_token = make_logged_in_user(client, "carol")
    rel_ac = make_relationship(client, alice_token, "carol", accept=True)
    rel_bc = make_relationship(client, bob_token, "carol", accept=True)

    expense_ab_pending = make_expense(
        client,
        alice_token,
        relationship_id=rel_ab["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )
    expense_ab_confirmed = make_expense(
        client,
        alice_token,
        relationship_id=rel_ab["id"],
        payer_user_id=alice["id"],
        total_cents=600,
        shares=[
            {"user_id": alice["id"], "amount_cents": 300},
            {"user_id": bob["id"], "amount_cents": 300},
        ],
        confirm_token=bob_token,
    )
    expense_ac = make_expense(
        client,
        alice_token,
        relationship_id=rel_ac["id"],
        payer_user_id=alice["id"],
        total_cents=400,
        shares=[
            {"user_id": alice["id"], "amount_cents": 200},
            {"user_id": carol["id"], "amount_cents": 200},
        ],
    )
    expense_bc = make_expense(
        client,
        bob_token,
        relationship_id=rel_bc["id"],
        payer_user_id=bob["id"],
        total_cents=800,
        shares=[
            {"user_id": bob["id"], "amount_cents": 400},
            {"user_id": carol["id"], "amount_cents": 400},
        ],
    )

    # Alice's view: all expenses where she is a party (ab + ac), but not bc.
    alice_list = client.get("/api/v1/expenses", headers=auth_headers(alice_token)).get_json()
    alice_ids = {e["id"] for e in alice_list["items"]}
    assert alice_ids == {
        expense_ab_pending["id"],
        expense_ab_confirmed["id"],
        expense_ac["id"],
    }
    assert expense_bc["id"] not in alice_ids

    # Filter by relationship_id.
    alice_ab = client.get(
        f"/api/v1/expenses?relationship_id={rel_ab['id']}",
        headers=auth_headers(alice_token),
    ).get_json()
    assert {e["id"] for e in alice_ab["items"]} == {
        expense_ab_pending["id"],
        expense_ab_confirmed["id"],
    }

    # Filter by status.
    alice_confirmed = client.get(
        "/api/v1/expenses?status=confirmed",
        headers=auth_headers(alice_token),
    ).get_json()
    assert {e["id"] for e in alice_confirmed["items"]} == {expense_ab_confirmed["id"]}

    # Most-recent-first ordering.
    assert [e["id"] for e in alice_list["items"]] == sorted(
        [e["id"] for e in alice_list["items"]], reverse=True
    )

    # Carol cannot see ab.
    carol_list = client.get("/api/v1/expenses", headers=auth_headers(carol_token)).get_json()
    assert expense_ab_pending["id"] not in {e["id"] for e in carol_list["items"]}


def test_get_detail_of_non_party_expense_returns_404(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    _, carol_token = make_logged_in_user(client, "carol")
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

    response = client.get(
        f"/api/v1/expenses/{expense['id']}",
        headers=auth_headers(carol_token),
    )
    assert response.status_code == 404
    assert response.get_json()["error"]["code"] == "not_found"


# --- pagination ------------------------------------------------------


def _create_expenses(
    client: FlaskClient,
    creator_token: str,
    *,
    relationship_id: int,
    payer_user_id: int,
    counterparty_id: int,
    count: int,
    confirm_token: str | None = None,
):
    """Create ``count`` pending expenses against ``relationship_id``."""
    for i in range(count):
        make_expense(
            client,
            creator_token,
            relationship_id=relationship_id,
            payer_user_id=payer_user_id,
            total_cents=100 + i,
            shares=[
                {"user_id": payer_user_id, "amount_cents": 50},
                {"user_id": counterparty_id, "amount_cents": 50 + i},
            ],
            description=f"Expense {i}",
            confirm_token=confirm_token,
        )


def test_expenses_pagination_default_and_last_page(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expected_total = 55
    _create_expenses(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        counterparty_id=bob["id"],
        count=expected_total,
    )

    page1 = client.get("/api/v1/expenses", headers=auth_headers(alice_token)).get_json()
    assert page1["limit"] == 50
    assert page1["offset"] == 0
    assert page1["total"] == expected_total
    assert page1["has_more"] is True
    assert len(page1["items"]) == 50

    page2 = client.get("/api/v1/expenses?offset=50", headers=auth_headers(alice_token)).get_json()
    assert page2["offset"] == 50
    assert page2["has_more"] is False
    assert len(page2["items"]) == 5


def test_expenses_pagination_custom_limit_and_offset(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    _create_expenses(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        counterparty_id=bob["id"],
        count=7,
    )

    body = client.get(
        "/api/v1/expenses?limit=3&offset=2", headers=auth_headers(alice_token)
    ).get_json()
    assert body["limit"] == 3
    assert body["offset"] == 2
    assert body["total"] == 7
    assert body["has_more"] is True
    assert len(body["items"]) == 3


def test_expenses_pagination_filters_compose(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    # 6 pending, 4 confirmed.
    _create_expenses(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        counterparty_id=bob["id"],
        count=6,
    )
    _create_expenses(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        counterparty_id=bob["id"],
        count=4,
        confirm_token=bob_token,
    )

    body = client.get(
        "/api/v1/expenses?status=confirmed&limit=10",
        headers=auth_headers(alice_token),
    ).get_json()
    assert body["total"] == 4
    assert body["limit"] == 10
    assert body["has_more"] is False
    assert all(item["status"] == "confirmed" for item in body["items"])


def test_expenses_pagination_invalid_limit_returns_422(client: FlaskClient) -> None:
    _, _, alice_token, _, _ = _two_party_setup(client)

    over = client.get("/api/v1/expenses?limit=201", headers=auth_headers(alice_token))
    assert over.status_code == 422
    assert over.get_json()["error"]["code"] == "invalid_pagination"

    zero = client.get("/api/v1/expenses?limit=0", headers=auth_headers(alice_token))
    assert zero.status_code == 422


def test_expenses_pagination_excludes_other_users_rows(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel_ab = _two_party_setup(client)
    carol, carol_token = make_logged_in_user(client, "carol")
    rel_bc = make_relationship(client, carol_token, "bob", accept=True)

    # 3 visible to Alice.
    _create_expenses(
        client,
        alice_token,
        relationship_id=rel_ab["id"],
        payer_user_id=alice["id"],
        counterparty_id=bob["id"],
        count=3,
    )
    # 2 invisible to Alice (Bob/Carol relationship).
    _create_expenses(
        client,
        carol_token,
        relationship_id=rel_bc["id"],
        payer_user_id=carol["id"],
        counterparty_id=bob["id"],
        count=2,
    )

    body = client.get("/api/v1/expenses", headers=auth_headers(alice_token)).get_json()
    assert body["total"] == 3
    assert len(body["items"]) == 3
