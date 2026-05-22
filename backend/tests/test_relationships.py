"""Relationship endpoint tests."""

from __future__ import annotations

from flask.testing import FlaskClient

from tests.factories import (
    auth_headers,
    login,
    make_logged_in_user,
    make_relationship,
    make_user,
)


def test_invite_by_username_succeeds(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice", display_name="Alice")
    bob = make_user(client, "bob", display_name="Bob")

    response = client.post(
        "/api/v1/relationships",
        json={"username": "bob"},
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 201
    body = response.get_json()
    assert isinstance(body["id"], int)
    assert body["inviting_user"] == {
        "id": alice["id"],
        "username": "alice",
        "display_name": "Alice",
    }
    assert body["invited_user"] == {
        "id": bob["id"],
        "username": "bob",
        "display_name": "Bob",
    }
    assert body["status"] == "pending"
    assert body["created_at"].endswith("Z")


def test_invite_nonexistent_username_returns_404(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")

    response = client.post(
        "/api/v1/relationships",
        json={"username": "nobody"},
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 404
    assert response.get_json()["error"]["code"] == "user_not_found"


def test_self_invite_returns_422(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")

    response = client.post(
        "/api/v1/relationships",
        json={"username": "alice"},
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "cannot_invite_self"


def test_same_direction_duplicate_invite_returns_409(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    make_user(client, "bob")
    make_relationship(client, alice_token, "bob", accept=False)

    response = client.post(
        "/api/v1/relationships",
        json={"username": "bob"},
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_exists"


def test_inverse_direction_duplicate_invite_returns_409(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    make_relationship(client, alice_token, "bob", accept=False)

    response = client.post(
        "/api/v1/relationships",
        json={"username": "alice"},
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_exists"


def test_reinvite_after_rejection_succeeds(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    reject_resp = client.post(
        f"/api/v1/relationships/{rel['id']}/reject",
        headers=auth_headers(bob_token),
    )
    assert reject_resp.status_code == 200
    assert reject_resp.get_json()["status"] == "rejected"

    # Same direction re-invite works.
    same_dir = client.post(
        "/api/v1/relationships",
        json={"username": "bob"},
        headers=auth_headers(alice_token),
    )
    assert same_dir.status_code == 201

    # Reject the new pending one so we can try the inverse.
    client.post(
        f"/api/v1/relationships/{same_dir.get_json()['id']}/reject",
        headers=auth_headers(bob_token),
    )

    inverse_dir = client.post(
        "/api/v1/relationships",
        json={"username": "alice"},
        headers=auth_headers(bob_token),
    )
    assert inverse_dir.status_code == 201


def test_list_returns_only_callers_relationships_filtered_by_status(
    client: FlaskClient,
) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    _, carol_token = make_logged_in_user(client, "carol")

    accepted = make_relationship(client, alice_token, "bob", accept=True)
    pending = make_relationship(client, alice_token, "carol", accept=False)

    # Bob also has a relationship with Carol; Alice should not see it.
    make_relationship(client, bob_token, "carol", accept=True)

    all_alice = client.get("/api/v1/relationships", headers=auth_headers(alice_token))
    assert all_alice.status_code == 200
    body = all_alice.get_json()
    ids = {rel["id"] for rel in body["items"]}
    assert ids == {accepted["id"], pending["id"]}

    pending_only = client.get(
        "/api/v1/relationships?status=pending",
        headers=auth_headers(alice_token),
    )
    assert pending_only.status_code == 200
    pending_items = pending_only.get_json()["items"]
    assert len(pending_items) == 1
    assert pending_items[0]["id"] == pending["id"]

    accepted_only = client.get(
        "/api/v1/relationships?status=accepted",
        headers=auth_headers(alice_token),
    )
    assert accepted_only.status_code == 200
    accepted_items = accepted_only.get_json()["items"]
    assert len(accepted_items) == 1
    assert accepted_items[0]["id"] == accepted["id"]

    # Carol's view should include the Carol/Bob relationship and the
    # pending Alice/Carol invite, but not Alice/Bob.
    all_carol = client.get("/api/v1/relationships", headers=auth_headers(carol_token)).get_json()
    carol_ids = {rel["id"] for rel in all_carol["items"]}
    assert pending["id"] in carol_ids
    assert accepted["id"] not in carol_ids


def test_accept_by_invited_user_succeeds(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    response = client.post(
        f"/api/v1/relationships/{rel['id']}/accept",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 200
    assert response.get_json()["status"] == "accepted"


def test_accept_by_inviter_returns_403(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    make_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    response = client.post(
        f"/api/v1/relationships/{rel['id']}/accept",
        headers=auth_headers(alice_token),
    )

    assert response.status_code == 403
    assert response.get_json()["error"]["code"] == "not_invited_party"


def test_accept_of_non_pending_returns_409(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)

    response = client.post(
        f"/api/v1/relationships/{rel['id']}/accept",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_not_pending"


def test_reject_by_either_party_succeeds(client: FlaskClient) -> None:
    # Inviter (Alice) withdraws.
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    by_inviter = client.post(
        f"/api/v1/relationships/{rel['id']}/reject",
        headers=auth_headers(alice_token),
    )
    assert by_inviter.status_code == 200
    assert by_inviter.get_json()["status"] == "rejected"

    # And invitee can also reject (fresh pending row).
    make_user(client, "carol")
    rel2 = make_relationship(client, alice_token, "carol", accept=False)
    carol_token = login(client, "carol")
    by_invitee = client.post(
        f"/api/v1/relationships/{rel2['id']}/reject",
        headers=auth_headers(carol_token),
    )
    assert by_invitee.status_code == 200
    assert by_invitee.get_json()["status"] == "rejected"


def test_reject_of_non_pending_returns_409(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)

    response = client.post(
        f"/api/v1/relationships/{rel['id']}/reject",
        headers=auth_headers(bob_token),
    )

    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "relationship_not_pending"


def test_get_detail_by_non_party_returns_404(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    _, carol_token = make_logged_in_user(client, "carol")
    rel = make_relationship(client, alice_token, "bob", accept=True)

    response = client.get(
        f"/api/v1/relationships/{rel['id']}",
        headers=auth_headers(carol_token),
    )
    assert response.status_code == 404
    assert response.get_json()["error"]["code"] == "not_found"


# --- bundled invite + first expense ----------------------------------


def _bundled_invite(
    client: FlaskClient,
    inviter_token: str,
    invitee_username: str,
    first_expense: dict | None,
):
    body: dict = {"username": invitee_username}
    if first_expense is not None:
        body["first_expense"] = first_expense
    return client.post(
        "/api/v1/relationships",
        json=body,
        headers=auth_headers(inviter_token),
    )


def test_bundled_invite_creates_relationship_and_expense(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob = make_user(client, "bob")

    response = _bundled_invite(
        client,
        alice_token,
        "bob",
        {
            "payer_user_id": alice["id"],
            "total_cents": 5000,
            "description": "Lunch at Pat's",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 2500},
                {"user_id": bob["id"], "amount_cents": 2500},
            ],
        },
    )

    assert response.status_code == 201
    body = response.get_json()
    assert set(body.keys()) == {"relationship", "expense"}
    rel = body["relationship"]
    expense = body["expense"]
    assert rel["status"] == "pending"
    assert rel["inviting_user"]["id"] == alice["id"]
    assert rel["invited_user"]["id"] == bob["id"]
    assert expense["relationship_id"] == rel["id"]
    assert expense["status"] == "pending"
    assert expense["payer_user_id"] == alice["id"]
    assert expense["total_cents"] == 5000
    assert expense["description"] == "Lunch at Pat's"
    assert expense["created_by_user_id"] == alice["id"]
    assert expense["reverses_expense_id"] is None
    assert {(s["user_id"], s["amount_cents"]) for s in expense["shares"]} == {
        (alice["id"], 2500),
        (bob["id"], 2500),
    }


def test_bundled_invite_without_first_expense_uses_phase2_shape(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    make_user(client, "bob")

    response = _bundled_invite(client, alice_token, "bob", first_expense=None)
    assert response.status_code == 201
    body = response.get_json()
    # Phase 2 shape: single relationship object directly, no envelope.
    assert "relationship" not in body
    assert "expense" not in body
    assert body["status"] == "pending"
    assert body["inviting_user"]["username"] == "alice"
    assert body["invited_user"]["username"] == "bob"


def test_bundled_invite_share_sum_mismatch_rolls_back_relationship(
    client: FlaskClient,
) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob = make_user(client, "bob")

    response = _bundled_invite(
        client,
        alice_token,
        "bob",
        {
            "payer_user_id": alice["id"],
            "total_cents": 5000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 2400},
                {"user_id": bob["id"], "amount_cents": 2500},
            ],
        },
    )

    assert response.status_code == 422
    body = response.get_json()["error"]
    assert body["code"] == "share_sum_mismatch"
    assert body["details"] == {"total_cents": 5000, "shares_sum_cents": 4900}

    # No relationship row should exist.
    listing = client.get("/api/v1/relationships", headers=auth_headers(alice_token))
    assert listing.status_code == 200
    assert listing.get_json()["total"] == 0
    assert listing.get_json()["items"] == []


def test_bundled_invite_invalid_payer_rolls_back_relationship(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob = make_user(client, "bob")
    carol = make_user(client, "carol")

    response = _bundled_invite(
        client,
        alice_token,
        "bob",
        {
            "payer_user_id": carol["id"],
            "total_cents": 1000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 500},
                {"user_id": bob["id"], "amount_cents": 500},
            ],
        },
    )

    assert response.status_code == 422
    assert response.get_json()["error"]["code"] == "invalid_payer"

    listing = client.get("/api/v1/relationships", headers=auth_headers(alice_token)).get_json()
    assert listing["total"] == 0


def test_bundled_invite_uses_phase2_invite_error_codes(client: FlaskClient) -> None:
    """The Phase 2 invite validations still run first and produce the same codes."""
    _, alice_token = make_logged_in_user(client, "alice")

    self_invite = _bundled_invite(
        client,
        alice_token,
        "alice",
        {
            "payer_user_id": 999,
            "total_cents": 100,
            "description": "x",
            "shares": [{"user_id": 999, "amount_cents": 100}],
        },
    )
    assert self_invite.status_code == 422
    assert self_invite.get_json()["error"]["code"] == "cannot_invite_self"


# --- reject cascade --------------------------------------------------


def test_reject_cascades_discard_on_pending_expenses(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")

    # Bundled invite stamps one pending expense on the new relationship.
    bundled = _bundled_invite(
        client,
        alice_token,
        "bob",
        {
            "payer_user_id": alice["id"],
            "total_cents": 2000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 1000},
                {"user_id": bob["id"], "amount_cents": 1000},
            ],
        },
    ).get_json()
    rel_id = bundled["relationship"]["id"]
    first_expense_id = bundled["expense"]["id"]

    # Bob (invitee) rejects.
    reject_resp = client.post(
        f"/api/v1/relationships/{rel_id}/reject",
        headers=auth_headers(bob_token),
    )
    assert reject_resp.status_code == 200

    # The bundled expense is now discarded with the cascade marker.
    expense = client.get(
        f"/api/v1/expenses/{first_expense_id}",
        headers=auth_headers(bob_token),
    ).get_json()
    assert expense["status"] == "discarded"
    assert expense["discarded_by_user_id"] == bob["id"]
    assert expense["rejection_reason"] == "Relationship rejected"
    assert expense["discarded_at"] is not None
    assert expense["discarded_at"].endswith("Z")


def test_reject_with_no_pending_expenses_still_succeeds(client: FlaskClient) -> None:
    """Phase 2 reject behaviour: still works on a bare pending relationship."""
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    response = client.post(
        f"/api/v1/relationships/{rel['id']}/reject",
        headers=auth_headers(bob_token),
    )
    assert response.status_code == 200
    assert response.get_json()["status"] == "rejected"


def test_accept_does_not_cascade_pending_expense(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")

    bundled = _bundled_invite(
        client,
        alice_token,
        "bob",
        {
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Lunch",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 500},
                {"user_id": bob["id"], "amount_cents": 500},
            ],
        },
    ).get_json()
    rel_id = bundled["relationship"]["id"]
    expense_id = bundled["expense"]["id"]

    accept_resp = client.post(
        f"/api/v1/relationships/{rel_id}/accept",
        headers=auth_headers(bob_token),
    )
    assert accept_resp.status_code == 200

    expense = client.get(
        f"/api/v1/expenses/{expense_id}",
        headers=auth_headers(alice_token),
    ).get_json()
    assert expense["status"] == "pending"

    # Counterparty (Bob) can confirm it the normal way.
    confirm_resp = client.post(
        f"/api/v1/expenses/{expense_id}/confirm",
        headers=auth_headers(bob_token),
    )
    assert confirm_resp.status_code == 200
    assert confirm_resp.get_json()["status"] == "confirmed"


# --- pagination ------------------------------------------------------


def test_relationships_pagination_default_and_subsequent_pages(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    # 55 invitees, each gets a pending invite from Alice.
    expected_total = 55
    for i in range(expected_total):
        make_user(client, f"user{i:02d}")
        resp = client.post(
            "/api/v1/relationships",
            json={"username": f"user{i:02d}"},
            headers=auth_headers(alice_token),
        )
        assert resp.status_code == 201

    page1 = client.get("/api/v1/relationships", headers=auth_headers(alice_token)).get_json()
    assert page1["limit"] == 50
    assert page1["offset"] == 0
    assert page1["total"] == expected_total
    assert page1["has_more"] is True
    assert len(page1["items"]) == 50

    page2 = client.get(
        "/api/v1/relationships?offset=50",
        headers=auth_headers(alice_token),
    ).get_json()
    assert page2["limit"] == 50
    assert page2["offset"] == 50
    assert page2["total"] == expected_total
    assert page2["has_more"] is False
    assert len(page2["items"]) == 5

    # IDs across pages cover the full set with no duplicates.
    all_ids = {r["id"] for r in page1["items"]} | {r["id"] for r in page2["items"]}
    assert len(all_ids) == expected_total


def test_relationships_pagination_filters_compose(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    # Five invites; reject two so we have a mix of statuses.
    for i in range(5):
        username = f"user{i}"
        make_logged_in_user(client, username)
        resp = client.post(
            "/api/v1/relationships",
            json={"username": username},
            headers=auth_headers(alice_token),
        )
        assert resp.status_code == 201
        rel_id = resp.get_json()["id"]
        if i < 2:
            client.post(
                f"/api/v1/relationships/{rel_id}/reject",
                headers=auth_headers(alice_token),
            )

    body = client.get(
        "/api/v1/relationships?status=pending&limit=2",
        headers=auth_headers(alice_token),
    ).get_json()
    assert body["total"] == 3
    assert body["limit"] == 2
    assert body["offset"] == 0
    assert body["has_more"] is True
    assert len(body["items"]) == 2
    assert all(item["status"] == "pending" for item in body["items"])


def test_relationships_pagination_empty_result(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    body = client.get(
        "/api/v1/relationships?limit=10&offset=3",
        headers=auth_headers(alice_token),
    ).get_json()
    assert body == {"items": [], "total": 0, "limit": 10, "offset": 3, "has_more": False}


def test_relationships_pagination_limit_bounds_and_types(client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(client, "alice")

    # limit=200 is the inclusive upper bound.
    ok = client.get("/api/v1/relationships?limit=200", headers=auth_headers(alice_token))
    assert ok.status_code == 200

    # limit=201 is out of range.
    over = client.get("/api/v1/relationships?limit=201", headers=auth_headers(alice_token))
    assert over.status_code == 422
    body = over.get_json()["error"]
    assert body["code"] == "invalid_pagination"
    assert body["details"]["parameter"] == "limit"

    # limit=0 is below the lower bound.
    zero = client.get("/api/v1/relationships?limit=0", headers=auth_headers(alice_token))
    assert zero.status_code == 422
    assert zero.get_json()["error"]["code"] == "invalid_pagination"

    # Non-integer limit is rejected.
    abc = client.get("/api/v1/relationships?limit=abc", headers=auth_headers(alice_token))
    assert abc.status_code == 422
    assert abc.get_json()["error"]["code"] == "invalid_pagination"

    # Negative offset is rejected.
    neg = client.get("/api/v1/relationships?offset=-1", headers=auth_headers(alice_token))
    assert neg.status_code == 422
    assert neg.get_json()["error"]["code"] == "invalid_pagination"
    assert neg.get_json()["error"]["details"]["parameter"] == "offset"
