"""Password-reset flow tests (email collection, request, confirm)."""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from flask import Flask
from flask.testing import FlaskClient

from app.extensions import db
from app.models import AuthToken, PasswordResetToken, User
from app.services.email_sender import EmailMessage
from tests.factories import auth_headers, make_logged_in_user, make_relationship

_TOKEN_RE = re.compile(r"[A-Za-z0-9_-]{30,}")


class _CapturingSender:
    """Test double that records every EmailMessage it is handed."""

    def __init__(self) -> None:
        self.sent: list[EmailMessage] = []

    def send(self, message: EmailMessage) -> None:
        self.sent.append(message)


def _install_sender(app: Flask) -> _CapturingSender:
    sender = _CapturingSender()
    app.extensions["email_sender"] = sender
    return sender


def _token_from(sender: _CapturingSender) -> str:
    assert len(sender.sent) == 1, sender.sent
    match = _TOKEN_RE.search(sender.sent[0].body)
    assert match, sender.sent[0].body
    return match.group(0)


def _register_with_email(client: FlaskClient, username: str, email: str) -> dict:
    resp = client.post(
        "/api/v1/auth/register",
        json={
            "username": username,
            "password": f"pw-{username}",
            "display_name": username.capitalize(),
            "email": email,
        },
    )
    assert resp.status_code == 201, resp.get_json()
    return resp.get_json()


# --- email as account data --------------------------------------------------


def test_register_with_email_and_me_shows_it(client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
    ).get_json()["token"]
    me = client.get("/api/v1/auth/me", headers=auth_headers(token)).get_json()
    assert me["email"] == "alice@example.com"


def test_email_is_normalised(client: FlaskClient) -> None:
    body = _register_with_email(client, "alice", "  Alice@Example.COM ")
    assert body["id"]
    token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
    ).get_json()["token"]
    me = client.get("/api/v1/auth/me", headers=auth_headers(token)).get_json()
    assert me["email"] == "alice@example.com"


def test_register_invalid_email_rejected(client: FlaskClient) -> None:
    resp = client.post(
        "/api/v1/auth/register",
        json={
            "username": "alice",
            "password": "pw-alice",
            "display_name": "Alice",
            "email": "not-an-email",
        },
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_email"


def test_duplicate_email_rejected(client: FlaskClient) -> None:
    _register_with_email(client, "alice", "shared@example.com")
    resp = client.post(
        "/api/v1/auth/register",
        json={
            "username": "bob",
            "password": "pw-bob",
            "display_name": "Bob",
            "email": "shared@example.com",
        },
    )
    assert resp.status_code == 409
    assert resp.get_json()["error"]["code"] == "email_taken"


def test_set_and_clear_email_via_profile(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "alice")
    set_resp = client.patch(
        "/api/v1/auth/me", json={"email": "alice@example.com"}, headers=auth_headers(token)
    )
    assert set_resp.status_code == 200
    assert set_resp.get_json()["email"] == "alice@example.com"

    clear_resp = client.patch("/api/v1/auth/me", json={"email": None}, headers=auth_headers(token))
    assert clear_resp.status_code == 200
    assert clear_resp.get_json()["email"] is None


def test_profile_email_collision_rejected(client: FlaskClient) -> None:
    _register_with_email(client, "alice", "taken@example.com")
    _bob, bob_token = make_logged_in_user(client, "bob")
    resp = client.patch(
        "/api/v1/auth/me", json={"email": "taken@example.com"}, headers=auth_headers(bob_token)
    )
    assert resp.status_code == 409
    assert resp.get_json()["error"]["code"] == "email_taken"


def test_counterparty_never_sees_email(client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    make_logged_in_user(client, "bob")
    alice_token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
    ).get_json()["token"]
    rel = make_relationship(client, alice_token, "bob", accept=True)
    bob_token = client.post(
        "/api/v1/auth/login", json={"username": "bob", "password": "pw-bob"}
    ).get_json()["token"]

    fetched = client.get(
        f"/api/v1/relationships/{rel['id']}", headers=auth_headers(bob_token)
    ).get_json()
    # Alice's brief in the relationship must not carry her email.
    briefs = [fetched["inviting_user"], fetched["invited_user"]]
    for brief in briefs:
        assert "email" not in brief


def test_export_includes_email(client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
    ).get_json()["token"]
    export = client.get("/api/v1/auth/me/export", headers=auth_headers(token)).get_json()
    assert export["user"]["email"] == "alice@example.com"


# --- request ----------------------------------------------------------------


def test_request_unknown_email_is_204_and_sends_nothing(app: Flask, client: FlaskClient) -> None:
    sender = _install_sender(app)
    resp = client.post("/api/v1/auth/password-reset/request", json={"email": "nobody@example.com"})
    assert resp.status_code == 204
    assert sender.sent == []


def test_request_missing_body_is_204(app: Flask, client: FlaskClient) -> None:
    sender = _install_sender(app)
    resp = client.post("/api/v1/auth/password-reset/request", json={})
    assert resp.status_code == 204
    assert sender.sent == []


def test_request_known_email_sends_reset(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    sender = _install_sender(app)
    resp = client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    assert resp.status_code == 204
    assert len(sender.sent) == 1
    assert sender.sent[0].to == "alice@example.com"


# --- confirm ----------------------------------------------------------------


def test_full_reset_flow_revokes_sessions(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    # Two live sessions.
    old_token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
    ).get_json()["token"]
    client.post("/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"})

    sender = _install_sender(app)
    client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    reset_token = _token_from(sender)

    confirm = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": reset_token, "new_password": "brand-new-pw"},
    )
    assert confirm.status_code == 204

    # Old session is revoked.
    assert client.get("/api/v1/auth/me", headers=auth_headers(old_token)).status_code == 401
    # Old password no longer works; new one does.
    assert (
        client.post(
            "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
        ).status_code
        == 401
    )
    assert (
        client.post(
            "/api/v1/auth/login", json={"username": "alice", "password": "brand-new-pw"}
        ).status_code
        == 200
    )


def test_confirm_invalid_token(client: FlaskClient) -> None:
    resp = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": "not-a-real-token", "new_password": "brand-new-pw"},
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_token"


def test_confirm_weak_password(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    sender = _install_sender(app)
    client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    reset_token = _token_from(sender)
    resp = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": reset_token, "new_password": "short"},
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "weak_password"


def test_confirm_token_is_single_use(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    sender = _install_sender(app)
    client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    reset_token = _token_from(sender)

    first = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": reset_token, "new_password": "brand-new-pw"},
    )
    assert first.status_code == 204
    second = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": reset_token, "new_password": "another-new-pw"},
    )
    assert second.status_code == 422
    assert second.get_json()["error"]["code"] == "invalid_token"


def test_confirm_expired_token(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    sender = _install_sender(app)
    client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    reset_token = _token_from(sender)

    # Force the token past its expiry.
    row = db.session.execute(db.select(PasswordResetToken)).scalar_one()
    row.expires_at = datetime.now(UTC) - timedelta(minutes=1)
    db.session.commit()

    resp = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": reset_token, "new_password": "brand-new-pw"},
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_token"


def test_new_request_invalidates_prior_token(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")

    sender1 = _install_sender(app)
    client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    first_token = _token_from(sender1)

    sender2 = _install_sender(app)
    client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    second_token = _token_from(sender2)

    # The first token was superseded.
    stale = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": first_token, "new_password": "brand-new-pw"},
    )
    assert stale.status_code == 422
    # The newest token still works.
    ok = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": second_token, "new_password": "brand-new-pw"},
    )
    assert ok.status_code == 204


def test_deleted_account_cannot_reset(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
    ).get_json()["token"]
    # Delete (anonymize) the account.
    assert (
        client.delete(
            "/api/v1/auth/me", json={"password": "pw-alice"}, headers=auth_headers(token)
        ).status_code
        == 204
    )

    sender = _install_sender(app)
    resp = client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    assert resp.status_code == 204
    assert sender.sent == []
    # And no reset tokens linger for the tombstoned user.
    assert db.session.execute(db.select(PasswordResetToken)).first() is None


def test_deletion_clears_email_and_frees_it(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
    ).get_json()["token"]
    client.delete("/api/v1/auth/me", json={"password": "pw-alice"}, headers=auth_headers(token))

    # The freed address can be claimed by a new account.
    reused = _register_with_email(client, "bob", "alice@example.com")
    assert reused["username"] == "bob"

    deleted = db.session.execute(
        db.select(User).where(User.username.like("deleted_user_%"))
    ).scalar_one()
    assert deleted.email is None


def test_confirm_does_not_revoke_after_used(app: Flask, client: FlaskClient) -> None:
    # A reset revokes sessions once; a fresh login afterward stays valid.
    _register_with_email(client, "alice", "alice@example.com")
    sender = _install_sender(app)
    client.post("/api/v1/auth/password-reset/request", json={"email": "alice@example.com"})
    reset_token = _token_from(sender)
    client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"token": reset_token, "new_password": "brand-new-pw"},
    )
    new_token = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "brand-new-pw"}
    ).get_json()["token"]
    assert client.get("/api/v1/auth/me", headers=auth_headers(new_token)).status_code == 200
    # No auth tokens remain revoked-then-resurrected oddities: the new one is live.
    live = (
        db.session.execute(db.select(AuthToken).where(AuthToken.revoked_at.is_(None)))
        .scalars()
        .all()
    )
    assert len(live) == 1
