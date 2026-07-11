"""Push device-token registration tests."""

from __future__ import annotations

from flask import Flask
from flask.testing import FlaskClient
from sqlalchemy import select

from app.extensions import db
from app.models import DeviceToken
from tests.factories import auth_headers, make_logged_in_user


def _tokens() -> list[DeviceToken]:
    return list(db.session.execute(select(DeviceToken)).scalars().all())


def test_register_device_returns_device(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "alice")

    resp = client.post(
        "/api/v1/auth/devices",
        json={"token": "fcm-token-abc", "platform": "android"},
        headers=auth_headers(token),
    )
    assert resp.status_code == 200, resp.get_json()
    body = resp.get_json()
    assert body["platform"] == "android"
    assert body["created_at"].endswith("Z")
    assert body["last_seen_at"].endswith("Z")
    # The raw token is never echoed back.
    assert "token" not in body


def test_register_device_requires_auth(client: FlaskClient) -> None:
    resp = client.post("/api/v1/auth/devices", json={"token": "x"})
    assert resp.status_code == 401


def test_register_device_rejects_blank_token(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "alice")
    resp = client.post(
        "/api/v1/auth/devices",
        json={"token": "   "},
        headers=auth_headers(token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_token"


def test_register_device_rejects_unknown_platform(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "alice")
    resp = client.post(
        "/api/v1/auth/devices",
        json={"token": "fcm-token-abc", "platform": "windows-phone"},
        headers=auth_headers(token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_platform"


def test_register_same_token_twice_is_single_row(client: FlaskClient, app: Flask) -> None:
    _user, token = make_logged_in_user(client, "alice")
    for _ in range(2):
        resp = client.post(
            "/api/v1/auth/devices",
            json={"token": "fcm-token-abc"},
            headers=auth_headers(token),
        )
        assert resp.status_code == 200

    rows = _tokens()
    assert len(rows) == 1


def test_register_reassigns_token_to_new_user(client: FlaskClient, app: Flask) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")

    client.post(
        "/api/v1/auth/devices",
        json={"token": "shared-device-token"},
        headers=auth_headers(alice_token),
    )
    # The same physical device now signs in as Bob and re-registers.
    client.post(
        "/api/v1/auth/devices",
        json={"token": "shared-device-token"},
        headers=auth_headers(bob_token),
    )

    rows = _tokens()
    assert len(rows) == 1
    assert rows[0].user_id == bob["id"]


def test_unregister_removes_token(client: FlaskClient, app: Flask) -> None:
    _user, token = make_logged_in_user(client, "alice")
    client.post(
        "/api/v1/auth/devices",
        json={"token": "fcm-token-abc"},
        headers=auth_headers(token),
    )

    resp = client.delete(
        "/api/v1/auth/devices",
        json={"token": "fcm-token-abc"},
        headers=auth_headers(token),
    )
    assert resp.status_code == 204
    assert _tokens() == []


def test_unregister_is_idempotent(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "alice")
    resp = client.delete(
        "/api/v1/auth/devices",
        json={"token": "never-registered"},
        headers=auth_headers(token),
    )
    assert resp.status_code == 204


def test_deleting_account_removes_device_tokens(client: FlaskClient, app: Flask) -> None:
    _user, token = make_logged_in_user(client, "alice")
    client.post(
        "/api/v1/auth/devices",
        json={"token": "alice-device"},
        headers=auth_headers(token),
    )
    assert len(_tokens()) == 1

    resp = client.delete(
        "/api/v1/auth/me",
        json={"password": "pw-alice"},
        headers=auth_headers(token),
    )
    assert resp.status_code == 204
    assert _tokens() == []


def test_unregister_only_affects_own_token(client: FlaskClient, app: Flask) -> None:
    _alice, alice_token = make_logged_in_user(client, "alice")
    _bob, bob_token = make_logged_in_user(client, "bob")

    client.post(
        "/api/v1/auth/devices",
        json={"token": "alice-device"},
        headers=auth_headers(alice_token),
    )
    # Bob cannot unregister Alice's device token.
    resp = client.delete(
        "/api/v1/auth/devices",
        json={"token": "alice-device"},
        headers=auth_headers(bob_token),
    )
    assert resp.status_code == 204
    assert len(_tokens()) == 1
