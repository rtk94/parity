"""Authentication flow tests."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from unittest.mock import patch

from argon2.exceptions import VerifyMismatchError
from flask import Flask
from flask.testing import FlaskClient
from sqlalchemy import select, text

from app.auth import security
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


def test_login_unknown_user_invokes_argon2_verify_once_for_timing(
    client: FlaskClient,
) -> None:
    """Login with an unknown username still runs argon2 verify once.

    Equalising the wall-clock cost of "user not found" with "user found,
    wrong password" defeats username enumeration via response-time
    analysis. The test cannot reliably assert on wall-clock timing, so
    instead it confirms that the equalisation code path actually runs:
    the hasher's ``verify`` method is invoked exactly once for an
    unknown username.

    ``argon2.PasswordHasher.verify`` is a slot method and cannot be
    patched in place, so the test swaps the module-level ``_hasher``
    reference for a mock; every ``_hasher.verify`` call in the
    security module resolves to ``mock_hasher.verify`` for the
    duration of the patch.
    """
    with patch.object(security, "_hasher") as mock_hasher:
        mock_hasher.verify.side_effect = VerifyMismatchError("dummy mismatch")
        response = client.post(
            "/api/v1/auth/login",
            json={"username": "no_such_user", "password": "anything"},
        )
        verify_call_count = mock_hasher.verify.call_count

    assert response.status_code == 401
    assert response.get_json()["error"]["code"] == "invalid_credentials"
    assert verify_call_count == 1


# --- change password -------------------------------------------------


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _alice_with_two_tokens(client: FlaskClient) -> tuple[str, str]:
    assert _register(client).status_code == 201
    token_a = _login(client).get_json()["token"]
    token_b = _login(client).get_json()["token"]
    return token_a, token_b


def test_change_password_succeeds_and_old_password_no_longer_works(
    client: FlaskClient,
) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    resp = client.post(
        "/api/v1/auth/change-password",
        json={
            "current_password": "correct horse battery staple",
            "new_password": "freshly-rotated-password",
        },
        headers=_bearer(token),
    )
    assert resp.status_code == 204
    assert resp.get_data() == b""

    # The current token still authenticates.
    me = client.get("/api/v1/auth/me", headers=_bearer(token))
    assert me.status_code == 200

    # Old password no longer works.
    old = _login(client, password="correct horse battery staple")
    assert old.status_code == 401
    assert old.get_json()["error"]["code"] == "invalid_credentials"

    # New password does.
    new = _login(client, password="freshly-rotated-password")
    assert new.status_code == 200


def test_change_password_revokes_other_sessions_but_keeps_current(
    client: FlaskClient,
) -> None:
    token_a, token_b = _alice_with_two_tokens(client)

    resp = client.post(
        "/api/v1/auth/change-password",
        json={
            "current_password": "correct horse battery staple",
            "new_password": "12345678",
        },
        headers=_bearer(token_a),
    )
    assert resp.status_code == 204

    # Token A is still alive.
    me_a = client.get("/api/v1/auth/me", headers=_bearer(token_a))
    assert me_a.status_code == 200

    # Token B was revoked.
    me_b = client.get("/api/v1/auth/me", headers=_bearer(token_b))
    assert me_b.status_code == 401


def test_change_password_wrong_current_returns_422_and_does_not_mutate(
    client: FlaskClient,
) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    resp = client.post(
        "/api/v1/auth/change-password",
        json={"current_password": "not the password", "new_password": "12345678"},
        headers=_bearer(token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_current_password"

    # Original password still works.
    assert _login(client).status_code == 200


def test_change_password_weak_new_password_returns_422(client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    resp = client.post(
        "/api/v1/auth/change-password",
        json={
            "current_password": "correct horse battery staple",
            "new_password": "short",
        },
        headers=_bearer(token),
    )
    assert resp.status_code == 422
    body = resp.get_json()["error"]
    assert body["code"] == "weak_password"
    assert body["details"] == {"min_length": 8}

    # Original password still works (no mutation).
    assert _login(client).status_code == 200


def test_change_password_missing_field_returns_422(client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    missing_new = client.post(
        "/api/v1/auth/change-password",
        json={"current_password": "x"},
        headers=_bearer(token),
    )
    assert missing_new.status_code == 422
    assert missing_new.get_json()["error"]["code"] == "invalid_request"

    missing_current = client.post(
        "/api/v1/auth/change-password",
        json={"new_password": "longenough"},
        headers=_bearer(token),
    )
    assert missing_current.status_code == 422
    assert missing_current.get_json()["error"]["code"] == "invalid_request"


def test_change_password_without_auth_returns_401(client: FlaskClient) -> None:
    resp = client.post(
        "/api/v1/auth/change-password",
        json={"current_password": "x", "new_password": "longenough"},
    )
    assert resp.status_code == 401


# --- token lifecycle -------------------------------------------------


def _set_token_columns(token: str, **columns) -> None:
    """Direct SQL update of an auth_token row, bypassing the ORM.

    The auth_token table has no immutability triggers (Phase 3 rationale
    spelled out in app/models/_triggers.py), so this is permitted.
    """
    set_clauses = ", ".join(f"{col} = :{col}" for col in columns)
    params = {**columns, "h": hash_token(token)}
    db.session.execute(
        text(f"UPDATE auth_token SET {set_clauses} WHERE token_hash = :h"),
        params,
    )
    db.session.commit()


def test_fresh_token_within_both_windows_authenticates(client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]
    resp = client.get("/api/v1/auth/me", headers=_bearer(token))
    assert resp.status_code == 200


def test_token_past_absolute_expiry_returns_401_token_expired(
    app: Flask, client: FlaskClient
) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    with app.app_context():
        _set_token_columns(token, expires_at="2020-01-01 00:00:00+00:00")

    resp = client.get("/api/v1/auth/me", headers=_bearer(token))
    assert resp.status_code == 401
    assert resp.get_json()["error"]["code"] == "token_expired"


def test_token_past_idle_expiry_returns_401_token_expired(app: Flask, client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    with app.app_context():
        # last_used_at far enough back to exceed the 30-day idle window;
        # expires_at left untouched (still 365 days out).
        old_last_used = datetime.now(UTC) - timedelta(days=60)
        _set_token_columns(token, last_used_at=old_last_used.isoformat())

    resp = client.get("/api/v1/auth/me", headers=_bearer(token))
    assert resp.status_code == 401
    assert resp.get_json()["error"]["code"] == "token_expired"


def test_idle_window_slides_on_successful_request(app: Flask, client: FlaskClient) -> None:
    """A successful auth refreshes ``last_used_at``, which slides the idle window forward."""
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    # Confirm normal request works.
    assert client.get("/api/v1/auth/me", headers=_bearer(token)).status_code == 200

    # Back-date last_used_at to 5 days ago — well within the 30-day window.
    with app.app_context():
        five_days_ago = datetime.now(UTC) - timedelta(days=5)
        _set_token_columns(token, last_used_at=five_days_ago.isoformat())

    # Second request still succeeds; it also slides last_used_at to now.
    assert client.get("/api/v1/auth/me", headers=_bearer(token)).status_code == 200


def test_refresh_issues_new_token_and_revokes_old(client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    old_token = _login(client).get_json()["token"]

    resp = client.post("/api/v1/auth/refresh", headers=_bearer(old_token))
    assert resp.status_code == 200
    body = resp.get_json()
    new_token = body["token"]
    assert isinstance(new_token, str) and new_token != old_token
    assert body["user"]["username"] == "alice"

    # New token works.
    assert client.get("/api/v1/auth/me", headers=_bearer(new_token)).status_code == 200
    # Old token does not.
    old_resp = client.get("/api/v1/auth/me", headers=_bearer(old_token))
    assert old_resp.status_code == 401


def test_refresh_sets_expires_at_one_lifetime_into_future(app: Flask, client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]
    before_refresh = datetime.now(UTC)

    resp = client.post("/api/v1/auth/refresh", headers=_bearer(token))
    new_token = resp.get_json()["token"]

    with app.app_context():
        row = db.session.execute(
            select(AuthToken).where(AuthToken.token_hash == hash_token(new_token))
        ).scalar_one()
        expires_at = row.expires_at
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=UTC)
        absolute_days = app.config["TOKEN_ABSOLUTE_LIFETIME_DAYS"]
        expected_low = before_refresh + timedelta(days=absolute_days - 1)
        expected_high = before_refresh + timedelta(days=absolute_days + 1)
        assert expected_low <= expires_at <= expected_high


def test_refresh_on_revoked_token_returns_401(client: FlaskClient) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]
    # Logout revokes.
    assert client.post("/api/v1/auth/logout", headers=_bearer(token)).status_code == 204

    resp = client.post("/api/v1/auth/refresh", headers=_bearer(token))
    assert resp.status_code == 401


def test_refresh_on_idle_expired_token_returns_token_expired(
    app: Flask, client: FlaskClient
) -> None:
    assert _register(client).status_code == 201
    token = _login(client).get_json()["token"]

    with app.app_context():
        old_last_used = datetime.now(UTC) - timedelta(days=60)
        _set_token_columns(token, last_used_at=old_last_used.isoformat())

    resp = client.post("/api/v1/auth/refresh", headers=_bearer(token))
    assert resp.status_code == 401
    assert resp.get_json()["error"]["code"] == "token_expired"
