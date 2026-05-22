"""Payment endpoint tests."""

from __future__ import annotations

from typing import Any

from flask.testing import FlaskClient

from tests.factories import (
    auth_headers,
    make_logged_in_user,
    make_payment,
    make_relationship,
    make_user,
)


def _two_party_setup(
    client: FlaskClient,
) -> tuple[dict[str, Any], dict[str, Any], str, str, dict[str, Any]]:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    return alice, bob, alice_token, bob_token, rel


def test_create_payment_succeeds(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/payments",
        json={
            "relationship_id": rel["id"],
            "from_user_id": alice["id"],
            "to_user_id": bob["id"],
            "amount_cents": 2500,
            "description": "Venmo",
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 201
    body = response.get_json()
    assert body["relationship_id"] == rel["id"]
    assert body["from_user_id"] == alice["id"]
    assert body["to_user_id"] == bob["id"]
    assert body["amount_cents"] == 2500
    assert body["description"] == "Venmo"
    assert body["created_by_user_id"] == alice["id"]
    assert body["status"] == "pending"
    assert body["created_at"].endswith("Z")
    assert body["confirmed_at"] is None
    assert body["reverses_payment_id"] is None


def test_create_with_outside_party_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    carol = make_user(client, "carol")

    response = client.post(
        "/api/v1/payments",
        json={
            "relationship_id": rel["id"],
            "from_user_id": alice["id"],
            "to_user_id": carol["id"],
            "amount_cents": 1000,
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "invalid_party"


def test_create_with_same_parties_returns_422(client: FlaskClient) -> None:
    alice, _, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/payments",
        json={
            "relationship_id": rel["id"],
            "from_user_id": alice["id"],
            "to_user_id": alice["id"],
            "amount_cents": 1000,
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "same_parties"


def test_create_zero_amount_returns_422(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)

    response = client.post(
        "/api/v1/payments",
        json={
            "relationship_id": rel["id"],
            "from_user_id": alice["id"],
            "to_user_id": bob["id"],
            "amount_cents": 0,
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "invalid_amount"


def test_create_against_pending_relationship_returns_409(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob = make_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    response = client.post(
        "/api/v1/payments",
        json={
            "relationship_id": rel["id"],
            "from_user_id": alice["id"],
            "to_user_id": bob["id"],
            "amount_cents": 1000,
        },
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_not_accepted"


def test_confirm_by_counterparty_succeeds(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    response = client.post(
        f"/api/v1/payments/{payment['id']}/confirm",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 200
    body = response.get_json()
    assert body["status"] == "confirmed"
    assert body["confirmed_by_user_id"] == bob["id"]


def test_confirm_by_creator_returns_403(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    response = client.post(
        f"/api/v1/payments/{payment['id']}/confirm",
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 403
    assert response.get_json()["error"]["code"] == "cannot_self_confirm"


def test_confirm_of_non_pending_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )

    response = client.post(
        f"/api/v1/payments/{payment['id']}/confirm",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "payment_not_pending"


def test_discard_with_reason_stores_reason(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    response = client.post(
        f"/api/v1/payments/{payment['id']}/discard",
        json={"reason": "Never sent that."},
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 200
    body = response.get_json()
    assert body["status"] == "discarded"
    assert body["discarded_by_user_id"] == bob["id"]
    assert body["rejection_reason"] == "Never sent that."


def test_discard_of_non_pending_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )

    response = client.post(
        f"/api/v1/payments/{payment['id']}/discard",
        headers=auth_headers(alice_token),
    )
    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "payment_not_pending"


def test_reverse_preserves_direction(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )

    response = client.post(
        f"/api/v1/payments/{payment['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 201
    body = response.get_json()
    assert body["status"] == "pending"
    assert body["reverses_payment_id"] == payment["id"]
    assert body["from_user_id"] == alice["id"]
    assert body["to_user_id"] == bob["id"]
    assert body["amount_cents"] == 1000
    assert body["description"] == f"Reversal of payment #{payment['id']}"


def test_reverse_of_pending_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    response = client.post(
        f"/api/v1/payments/{payment['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "original_not_confirmed"


def test_reverse_with_existing_pending_reversal_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )

    client.post(
        f"/api/v1/payments/{payment['id']}/reverse",
        headers=auth_headers(bob_token),
    )
    response = client.post(
        f"/api/v1/payments/{payment['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "already_reversed"


def test_reverse_after_discarded_reversal_succeeds(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )
    first_rev = client.post(
        f"/api/v1/payments/{payment['id']}/reverse",
        headers=auth_headers(bob_token),
    ).get_json()
    client.post(
        f"/api/v1/payments/{first_rev['id']}/discard",
        headers=auth_headers(bob_token),
    )

    second_rev = client.post(
        f"/api/v1/payments/{payment['id']}/reverse",
        headers=auth_headers(bob_token),
    )
    assert second_rev.status_code == 201


def test_reverse_of_reversal_returns_409(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )
    reversal = client.post(
        f"/api/v1/payments/{payment['id']}/reverse",
        headers=auth_headers(bob_token),
    ).get_json()
    client.post(
        f"/api/v1/payments/{reversal['id']}/confirm",
        headers=auth_headers(alice_token),
    )

    response = client.post(
        f"/api/v1/payments/{reversal['id']}/reverse",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "original_is_reversal"


def test_list_filters_and_scopes_to_party(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel_ab = _two_party_setup(client)
    carol, carol_token = make_logged_in_user(client, "carol")
    rel_bc = make_relationship(client, bob_token, "carol", accept=True)

    payment_ab_pending = make_payment(
        client,
        alice_token,
        relationship_id=rel_ab["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=500,
    )
    payment_ab_confirmed = make_payment(
        client,
        alice_token,
        relationship_id=rel_ab["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=700,
        confirm_token=bob_token,
    )
    payment_bc = make_payment(
        client,
        bob_token,
        relationship_id=rel_bc["id"],
        from_user_id=bob["id"],
        to_user_id=carol["id"],
        amount_cents=300,
    )

    alice_list = client.get("/api/v1/payments", headers=auth_headers(alice_token)).get_json()
    alice_ids = {p["id"] for p in alice_list["items"]}
    assert alice_ids == {payment_ab_pending["id"], payment_ab_confirmed["id"]}
    assert payment_bc["id"] not in alice_ids

    alice_confirmed = client.get(
        "/api/v1/payments?status=confirmed",
        headers=auth_headers(alice_token),
    ).get_json()
    assert {p["id"] for p in alice_confirmed["items"]} == {payment_ab_confirmed["id"]}

    alice_ab_only = client.get(
        f"/api/v1/payments?relationship_id={rel_ab['id']}",
        headers=auth_headers(alice_token),
    ).get_json()
    assert {p["id"] for p in alice_ab_only["items"]} == alice_ids

    # Most-recent-first ordering.
    list_ids = [p["id"] for p in alice_list["items"]]
    assert list_ids == sorted(list_ids, reverse=True)

    carol_list = client.get("/api/v1/payments", headers=auth_headers(carol_token)).get_json()
    assert payment_ab_pending["id"] not in {p["id"] for p in carol_list["items"]}


def test_get_detail_of_non_party_payment_returns_404(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    _, carol_token = make_logged_in_user(client, "carol")
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=500,
    )

    response = client.get(
        f"/api/v1/payments/{payment['id']}",
        headers=auth_headers(carol_token),
    )
    assert response.status_code == 404
    assert response.get_json()["error"]["code"] == "not_found"


# --- pagination ------------------------------------------------------


def _create_payments(
    client: FlaskClient,
    creator_token: str,
    *,
    relationship_id: int,
    from_user_id: int,
    to_user_id: int,
    count: int,
    confirm_token: str | None = None,
):
    for i in range(count):
        make_payment(
            client,
            creator_token,
            relationship_id=relationship_id,
            from_user_id=from_user_id,
            to_user_id=to_user_id,
            amount_cents=100 + i,
            confirm_token=confirm_token,
        )


def test_payments_pagination_default_and_last_page(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expected_total = 55
    _create_payments(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        count=expected_total,
    )

    page1 = client.get("/api/v1/payments", headers=auth_headers(alice_token)).get_json()
    assert page1["limit"] == 50
    assert page1["offset"] == 0
    assert page1["total"] == expected_total
    assert page1["has_more"] is True
    assert len(page1["items"]) == 50

    page2 = client.get("/api/v1/payments?offset=50", headers=auth_headers(alice_token)).get_json()
    assert page2["has_more"] is False
    assert len(page2["items"]) == 5


def test_payments_pagination_filters_compose(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    _create_payments(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        count=4,
    )
    _create_payments(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        count=3,
        confirm_token=bob_token,
    )

    body = client.get(
        "/api/v1/payments?status=confirmed&limit=10",
        headers=auth_headers(alice_token),
    ).get_json()
    assert body["total"] == 3
    assert body["has_more"] is False
    assert all(item["status"] == "confirmed" for item in body["items"])


def test_payments_pagination_invalid_offset_returns_422(client: FlaskClient) -> None:
    _, _, alice_token, _, _ = _two_party_setup(client)

    resp = client.get("/api/v1/payments?offset=-1", headers=auth_headers(alice_token))
    assert resp.status_code == 422
    body = resp.get_json()["error"]
    assert body["code"] == "invalid_pagination"
    assert body["details"]["parameter"] == "offset"


def test_payments_pagination_excludes_other_users_rows(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel_ab = _two_party_setup(client)
    carol, carol_token = make_logged_in_user(client, "carol")
    # Carol invites Bob; payments on that relationship must be
    # invisible to Alice even though Bob is a shared counterparty.
    rel_bc = make_relationship(client, carol_token, "bob", accept=True)

    _create_payments(
        client,
        alice_token,
        relationship_id=rel_ab["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        count=3,
    )
    _create_payments(
        client,
        carol_token,
        relationship_id=rel_bc["id"],
        from_user_id=carol["id"],
        to_user_id=bob["id"],
        count=2,
    )

    body = client.get("/api/v1/payments", headers=auth_headers(alice_token)).get_json()
    assert body["total"] == 3
    assert len(body["items"]) == 3
