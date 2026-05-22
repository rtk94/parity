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
