"""Authentication flow tests."""

from __future__ import annotations

from datetime import UTC, datetime

from flask import Flask
from flask.testing import FlaskClient
from sqlalchemy import select

from app.auth.security import hash_token
from app.extensions import db
from app.models import AuthToken


def _register(client: FlaskClient, **overrides):
    payload = {
        "username": "alice",
        "password": "correct horse battery staple",
        "display_name": "Alice",
    }
    payload.update(overrides)
    return client.post("/api/v1/auth/register", json=payload)


def _login(client: FlaskClient, **overrides):
    payload = {"username": "alice", "password": "correct horse battery staple"}
    payload.update(overrides)
    return client.post("/api/v1/auth/login", json=payload)


def test_full_auth_flow(client: FlaskClient) -> None:
    reg = _register(client)
    assert reg.status_code == 201
    reg_body = reg.get_json()
    assert reg_body["username"] == "alice"
    assert reg_body["display_name"] == "Alice"
    assert "id" in reg_body
    assert "password" not in reg_body
    assert "password_hash" not in reg_body

    login = _login(client)
    assert login.status_code == 200
    login_body = login.get_json()
    assert "token" in login_body and isinstance(login_body["token"], str)
    assert login_body["user"]["username"] == "alice"
    token = login_body["token"]

    headers = {"Authorization": f"Bearer {token}"}

    me = client.get("/api/v1/auth/me", headers=headers)
    assert me.status_code == 200
    assert me.get_json()["username"] == "alice"

    logout = client.post("/api/v1/auth/logout", headers=headers)
    assert logout.status_code == 204

    me_after = client.get("/api/v1/auth/me", headers=headers)
    assert me_after.status_code == 401


def test_login_wrong_password_returns_401(client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    response = _login(client, password="wrong password")
    assert response.status_code == 401
    assert response.get_json()["error"]["code"] == "invalid_credentials"


def test_missing_authorization_header_returns_401(client: FlaskClient) -> None:
    response = client.get("/api/v1/auth/me")
    assert response.status_code == 401


def test_malformed_authorization_header_returns_401(client: FlaskClient) -> None:
    response = client.get("/api/v1/auth/me", headers={"Authorization": "Token abcdef"})
    assert response.status_code == 401


def test_revoked_token_returns_401(app: Flask, client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    login = _login(client)
    token = login.get_json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    with app.app_context():
        row = db.session.execute(
            select(AuthToken).where(AuthToken.token_hash == hash_token(token))
        ).scalar_one()
        row.revoked_at = datetime.now(UTC)
        db.session.commit()

    response = client.get("/api/v1/auth/me", headers=headers)
    assert response.status_code == 401


def test_duplicate_username_returns_409(client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    response = _register(client, display_name="Other Alice")
    assert response.status_code == 409
    assert response.get_json()["error"]["code"] == "username_taken"
