"""Rate-limit tests.

Phase 1 and Phase 2 tests run against ``TestingConfig`` with
``RATELIMIT_ENABLED=False`` so they can issue many requests per fixture
without tripping limits. This module brings up a dedicated app with
rate limiting on via ``create_app("testing", overrides=...)``. Each
test gets a fresh storage backend (Flask-Limiter constructs new
in-memory storage on each ``init_app``); the ``limiter.reset()`` in
teardown is belt-and-braces against any cross-test leakage.
"""

from __future__ import annotations

from collections.abc import Generator

import pytest
from flask import Flask
from flask.testing import FlaskClient

from app import create_app
from app.extensions import db, limiter
from tests.factories import auth_headers, make_logged_in_user, make_user


@pytest.fixture
def rate_limited_app() -> Generator[Flask, None, None]:
    flask_app = create_app("testing", overrides={"RATELIMIT_ENABLED": True})
    with flask_app.app_context():
        db.create_all()
        try:
            yield flask_app
        finally:
            limiter.reset()
            db.session.remove()
            db.drop_all()
            db.engine.dispose()


@pytest.fixture
def rate_limited_client(rate_limited_app: Flask) -> FlaskClient:
    return rate_limited_app.test_client()


# --- per-IP login limit (5/minute) -----------------------------------


def test_login_ip_limit_returns_429_after_five_attempts(
    rate_limited_client: FlaskClient,
) -> None:
    make_user(rate_limited_client, "alice", password="correct-pw")

    for attempt in range(5):
        resp = rate_limited_client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "wrong"},
        )
        assert resp.status_code == 401, f"Attempt {attempt + 1}: {resp.get_json()}"

    resp = rate_limited_client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "wrong"},
    )
    assert resp.status_code == 429
    assert resp.get_json()["error"]["code"] == "rate_limited"


def test_login_ip_limit_does_not_affect_separate_ip(
    rate_limited_client: FlaskClient,
) -> None:
    make_user(rate_limited_client, "alice", password="correct-pw")

    # Exhaust limit from IP1.
    for _ in range(6):
        rate_limited_client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "wrong"},
            environ_overrides={"REMOTE_ADDR": "10.0.0.1"},
        )

    # IP2 is unaffected — first attempt from IP2 is just a 401.
    resp = rate_limited_client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "wrong"},
        environ_overrides={"REMOTE_ADDR": "10.0.0.2"},
    )
    assert resp.status_code == 401


# --- per-username login limit (20/hour) ------------------------------


def test_login_username_limit_returns_429_after_twenty_attempts(
    rate_limited_client: FlaskClient,
) -> None:
    make_user(rate_limited_client, "alice", password="correct-pw")

    # 20 attempts from distinct IPs to keep the per-IP limit out of the way.
    for i in range(20):
        resp = rate_limited_client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "wrong"},
            environ_overrides={"REMOTE_ADDR": f"10.1.0.{i + 1}"},
        )
        assert resp.status_code == 401, f"Attempt {i + 1}: {resp.get_json()}"

    resp = rate_limited_client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "wrong"},
        environ_overrides={"REMOTE_ADDR": "10.1.0.99"},
    )
    assert resp.status_code == 429


def test_login_with_missing_username_skips_username_limit(
    rate_limited_client: FlaskClient,
) -> None:
    # When the body lacks a usable username, ``login_username_key_func``
    # returns ``None`` and Flask-Limiter skips that limit. The per-IP
    # limit is the only one that should fire.
    for _ in range(5):
        resp = rate_limited_client.post(
            "/api/v1/auth/login",
            json={"password": "x"},
        )
        assert resp.status_code == 401

    resp = rate_limited_client.post(
        "/api/v1/auth/login",
        json={"password": "x"},
    )
    assert resp.status_code == 429


# --- register limit (5/hour per IP) ----------------------------------


def test_register_limit_returns_429_after_five_attempts(
    rate_limited_client: FlaskClient,
) -> None:
    for i in range(5):
        resp = rate_limited_client.post(
            "/api/v1/auth/register",
            json={
                "username": f"user{i}",
                "password": "pw",
                "display_name": f"User {i}",
            },
        )
        assert resp.status_code == 201, f"Attempt {i + 1}: {resp.get_json()}"

    resp = rate_limited_client.post(
        "/api/v1/auth/register",
        json={"username": "user5", "password": "pw", "display_name": "User 5"},
    )
    assert resp.status_code == 429


def test_register_limit_does_not_affect_separate_ip(
    rate_limited_client: FlaskClient,
) -> None:
    # Exhaust 6 registers from IP1.
    for i in range(6):
        rate_limited_client.post(
            "/api/v1/auth/register",
            json={
                "username": f"ip1_{i}",
                "password": "pw",
                "display_name": f"U{i}",
            },
            environ_overrides={"REMOTE_ADDR": "10.0.0.1"},
        )

    # IP2 should still be able to register fresh users.
    resp = rate_limited_client.post(
        "/api/v1/auth/register",
        json={"username": "fromip2", "password": "pw", "display_name": "U"},
        environ_overrides={"REMOTE_ADDR": "10.0.0.2"},
    )
    assert resp.status_code == 201


# --- authed write limit (60/minute per user) -------------------------


def test_authed_write_limit_returns_429_after_sixty_posts(
    rate_limited_client: FlaskClient,
) -> None:
    _, alice_token = make_logged_in_user(rate_limited_client, "alice")
    headers = auth_headers(alice_token)

    # 60 invites with an invalid body each return 400 (the service
    # raises BadRequestError on the empty username), but the rate limit
    # still increments per request.
    for i in range(60):
        resp = rate_limited_client.post(
            "/api/v1/relationships",
            json={"username": ""},
            headers=headers,
        )
        assert resp.status_code == 400, f"Attempt {i + 1}: {resp.get_json()}"

    resp = rate_limited_client.post(
        "/api/v1/relationships",
        json={"username": ""},
        headers=headers,
    )
    assert resp.status_code == 429
    assert resp.get_json()["error"]["code"] == "rate_limited"


def test_authed_write_limit_is_per_user(rate_limited_client: FlaskClient) -> None:
    _, alice_token = make_logged_in_user(rate_limited_client, "alice")
    _, bob_token = make_logged_in_user(rate_limited_client, "bob")

    # Drain Alice's write budget so any further write from Alice = 429.
    for _ in range(61):
        rate_limited_client.post(
            "/api/v1/relationships",
            json={"username": ""},
            headers=auth_headers(alice_token),
        )

    alice_blocked = rate_limited_client.post(
        "/api/v1/relationships",
        json={"username": ""},
        headers=auth_headers(alice_token),
    )
    assert alice_blocked.status_code == 429

    bob_ok = rate_limited_client.post(
        "/api/v1/relationships",
        json={"username": ""},
        headers=auth_headers(bob_token),
    )
    assert bob_ok.status_code == 400


# --- 429 envelope and Retry-After header -----------------------------


def test_429_body_matches_standard_envelope(rate_limited_client: FlaskClient) -> None:
    for i in range(5):
        rate_limited_client.post(
            "/api/v1/auth/register",
            json={
                "username": f"u{i}",
                "password": "pw",
                "display_name": "U",
            },
        )
    resp = rate_limited_client.post(
        "/api/v1/auth/register",
        json={"username": "u5", "password": "pw", "display_name": "U"},
    )
    assert resp.status_code == 429
    body = resp.get_json()
    assert body == {
        "error": {
            "code": "rate_limited",
            "message": "Rate limit exceeded. Try again later.",
            "details": {"limit": "5 per 1 hour"},
        }
    }


def test_429_response_includes_retry_after_header(
    rate_limited_client: FlaskClient,
) -> None:
    make_user(rate_limited_client, "alice", password="correct-pw")

    for _ in range(5):
        rate_limited_client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "wrong"},
        )
    resp = rate_limited_client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "wrong"},
    )
    assert resp.status_code == 429
    assert "Retry-After" in resp.headers


# --- GET endpoints have no limit -------------------------------------


def test_get_endpoints_are_not_rate_limited(rate_limited_client: FlaskClient) -> None:
    # 200 GET /health requests is well above any configured POST limit;
    # none should ever return 429.
    for _ in range(200):
        resp = rate_limited_client.get("/api/v1/health")
        assert resp.status_code == 200


# --- change-password limit (5/hour per user) -------------------------


def test_change_password_limit_returns_429_after_five_attempts(
    rate_limited_client: FlaskClient,
) -> None:
    _, alice_token = make_logged_in_user(rate_limited_client, "alice")
    headers = auth_headers(alice_token)

    # Five 422 attempts (wrong current password) still count against the limit.
    for attempt in range(5):
        resp = rate_limited_client.post(
            "/api/v1/auth/change-password",
            json={
                "current_password": f"wrong-{attempt}",
                "new_password": "longenough-new-password",
            },
            headers=headers,
        )
        assert resp.status_code == 422, f"Attempt {attempt + 1}: {resp.get_json()}"
        assert resp.get_json()["error"]["code"] == "invalid_current_password"

    resp = rate_limited_client.post(
        "/api/v1/auth/change-password",
        json={"current_password": "wrong-final", "new_password": "longenough-new-password"},
        headers=headers,
    )
    assert resp.status_code == 429
    assert resp.get_json()["error"]["code"] == "rate_limited"


# --- refresh limit (10/hour per user) --------------------------------


def test_refresh_limit_returns_429_after_ten_attempts(
    rate_limited_client: FlaskClient,
) -> None:
    _, token = make_logged_in_user(rate_limited_client, "alice")

    for attempt in range(10):
        resp = rate_limited_client.post("/api/v1/auth/refresh", headers=auth_headers(token))
        assert resp.status_code == 200, f"Attempt {attempt + 1}: {resp.get_json()}"
        token = resp.get_json()["token"]

    resp = rate_limited_client.post("/api/v1/auth/refresh", headers=auth_headers(token))
    assert resp.status_code == 429
    assert resp.get_json()["error"]["code"] == "rate_limited"
