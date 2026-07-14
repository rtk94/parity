"""Test factory helpers.

Each function drives the public HTTP API rather than reaching into the
ORM, so the helpers double as smoke tests for the routes they touch.
The shared password is ``f"pw-{username}"`` so callers only need to
supply the username when defaults are acceptable.
"""

from __future__ import annotations

from typing import Any

from flask.testing import FlaskClient


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def make_user(
    client: FlaskClient,
    username: str,
    password: str | None = None,
    display_name: str | None = None,
) -> dict[str, Any]:
    """Register a user and return its public dict (id, username, display_name)."""
    payload = {
        "username": username,
        "password": password or f"pw-{username}",
        "display_name": display_name or username.capitalize(),
    }
    response = client.post("/api/v1/auth/register", json=payload)
    assert response.status_code == 201, response.get_json()
    return response.get_json()


def login(client: FlaskClient, username: str, password: str | None = None) -> str:
    response = client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": password or f"pw-{username}"},
    )
    assert response.status_code == 200, response.get_json()
    return response.get_json()["token"]


def make_logged_in_user(
    client: FlaskClient,
    username: str,
    password: str | None = None,
    display_name: str | None = None,
) -> tuple[dict[str, Any], str]:
    user = make_user(client, username, password, display_name)
    token = login(client, username, password)
    return user, token


def make_relationship(
    client: FlaskClient,
    inviter_token: str,
    invitee_username: str,
    *,
    accept: bool = True,
    invitee_password: str | None = None,
    currency_code: str = "USD",
) -> dict[str, Any]:
    """Invite ``invitee_username`` from the inviter; optionally accept it.

    The invitee user must already exist.
    """
    invite_resp = client.post(
        "/api/v1/relationships",
        json={"username": invitee_username, "currency_code": currency_code},
        headers=auth_headers(inviter_token),
    )
    assert invite_resp.status_code == 201, invite_resp.get_json()
    relationship = invite_resp.get_json()
    if accept:
        invitee_token = login(client, invitee_username, invitee_password)
        accept_resp = client.post(
            f"/api/v1/relationships/{relationship['id']}/accept",
            headers=auth_headers(invitee_token),
        )
        assert accept_resp.status_code == 200, accept_resp.get_json()
        relationship = accept_resp.get_json()
    return relationship


def make_expense(
    client: FlaskClient,
    creator_token: str,
    *,
    relationship_id: int,
    payer_user_id: int,
    total_cents: int,
    shares: list[dict[str, int]],
    description: str = "An expense",
    confirm_token: str | None = None,
) -> dict[str, Any]:
    create_resp = client.post(
        "/api/v1/expenses",
        json={
            "relationship_id": relationship_id,
            "payer_user_id": payer_user_id,
            "total_cents": total_cents,
            "description": description,
            "shares": shares,
        },
        headers=auth_headers(creator_token),
    )
    assert create_resp.status_code == 201, create_resp.get_json()
    expense = create_resp.get_json()
    if confirm_token:
        confirm_resp = client.post(
            f"/api/v1/expenses/{expense['id']}/confirm",
            headers=auth_headers(confirm_token),
        )
        assert confirm_resp.status_code == 200, confirm_resp.get_json()
        expense = confirm_resp.get_json()
    return expense


def make_recurring_expense(
    client: FlaskClient,
    creator_token: str,
    *,
    relationship_id: int,
    payer_user_id: int,
    total_cents: int,
    shares: list[dict[str, int]],
    interval: str = "monthly",
    description: str = "Rent",
    category: str | None = None,
    start_on: str | None = None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "relationship_id": relationship_id,
        "payer_user_id": payer_user_id,
        "total_cents": total_cents,
        "description": description,
        "shares": shares,
        "interval": interval,
    }
    if category is not None:
        body["category"] = category
    if start_on is not None:
        body["start_on"] = start_on
    response = client.post(
        "/api/v1/recurring",
        json=body,
        headers=auth_headers(creator_token),
    )
    assert response.status_code == 201, response.get_json()
    return response.get_json()


def make_payment(
    client: FlaskClient,
    creator_token: str,
    *,
    relationship_id: int,
    from_user_id: int,
    to_user_id: int,
    amount_cents: int,
    description: str | None = None,
    confirm_token: str | None = None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "relationship_id": relationship_id,
        "from_user_id": from_user_id,
        "to_user_id": to_user_id,
        "amount_cents": amount_cents,
    }
    if description is not None:
        body["description"] = description
    create_resp = client.post(
        "/api/v1/payments",
        json=body,
        headers=auth_headers(creator_token),
    )
    assert create_resp.status_code == 201, create_resp.get_json()
    payment = create_resp.get_json()
    if confirm_token:
        confirm_resp = client.post(
            f"/api/v1/payments/{payment['id']}/confirm",
            headers=auth_headers(confirm_token),
        )
        assert confirm_resp.status_code == 200, confirm_resp.get_json()
        payment = confirm_resp.get_json()
    return payment
