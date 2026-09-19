"""Password-reset flow tests (email collection, request, confirm)."""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from flask import Flask
from flask.testing import FlaskClient

from app.extensions import db
from app.models import AuthToken, PasswordResetToken, User
from app.services.email_sender import EmailMessage
from app.services.password_reset import RESET_CODE_LENGTH
from tests.factories import auth_headers, make_logged_in_user, make_relationship

_CODE_RE = re.compile(rf"\b\d{{{RESET_CODE_LENGTH}}}\b")


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


def _code_from(sender: _CapturingSender) -> str:
    assert len(sender.sent) == 1, sender.sent
    match = _CODE_RE.search(sender.sent[0].body)
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


def _request_code(app: Flask, client: FlaskClient, email: str) -> str:
    sender = _install_sender(app)
    resp = client.post("/api/v1/auth/password-reset/request", json={"email": email})
    assert resp.status_code == 204
    return _code_from(sender)


def _confirm(client: FlaskClient, email: str, code: str, new_password: str):
    return client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": email, "code": code, "new_password": new_password},
    )


def _login_token(client: FlaskClient, username: str, password: str) -> str:
    return client.post(
        "/api/v1/auth/login", json={"username": username, "password": password}
    ).get_json()["token"]


# --- email as account data --------------------------------------------------


def test_register_with_email_and_me_shows_it(client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = _login_token(client, "alice", "pw-alice")
    me = client.get("/api/v1/auth/me", headers=auth_headers(token)).get_json()
    assert me["email"] == "alice@example.com"


def test_email_is_normalised(client: FlaskClient) -> None:
    body = _register_with_email(client, "alice", "  Alice@Example.COM ")
    assert body["id"]
    token = _login_token(client, "alice", "pw-alice")
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


def test_register_without_email_rejected(client: FlaskClient) -> None:
    # Email is the only self-service recovery channel, so an account may
    # not be created without one (ADR-0002 amendment).
    resp = client.post(
        "/api/v1/auth/register",
        json={"username": "alice", "password": "pw-alice", "display_name": "Alice"},
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_email"


def test_register_blank_email_rejected(client: FlaskClient) -> None:
    resp = client.post(
        "/api/v1/auth/register",
        json={
            "username": "alice",
            "password": "pw-alice",
            "display_name": "Alice",
            "email": "   ",
        },
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_email"


def test_register_sends_welcome_email(app: Flask, client: FlaskClient) -> None:
    sender = _install_sender(app)
    _register_with_email(client, "alice", "alice@example.com")
    assert len(sender.sent) == 1
    assert sender.sent[0].to == "alice@example.com"
    # It names the account so a wrong-address recipient can tell what it is.
    assert "alice" in sender.sent[0].body


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


def test_set_email_via_profile(client: FlaskClient) -> None:
    _user, token = make_logged_in_user(client, "alice")
    resp = client.patch(
        "/api/v1/auth/me", json={"email": "alice-new@example.com"}, headers=auth_headers(token)
    )
    assert resp.status_code == 200
    assert resp.get_json()["email"] == "alice-new@example.com"


def test_email_cannot_be_cleared_via_profile(client: FlaskClient) -> None:
    # Clearing used to be allowed; a live account must now keep a
    # recovery address, so both null and blank are refused.
    _user, token = make_logged_in_user(client, "alice")
    original = client.get("/api/v1/auth/me", headers=auth_headers(token)).get_json()["email"]

    for payload in ({"email": None}, {"email": ""}, {"email": "   "}):
        resp = client.patch("/api/v1/auth/me", json=payload, headers=auth_headers(token))
        assert resp.status_code == 422, payload
        assert resp.get_json()["error"]["code"] == "invalid_email"

    still = client.get("/api/v1/auth/me", headers=auth_headers(token)).get_json()["email"]
    assert still == original


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
    alice_token = _login_token(client, "alice", "pw-alice")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    bob_token = _login_token(client, "bob", "pw-bob")

    fetched = client.get(
        f"/api/v1/relationships/{rel['id']}", headers=auth_headers(bob_token)
    ).get_json()
    # Alice's brief in the relationship must not carry her email.
    briefs = [fetched["inviting_user"], fetched["invited_user"]]
    for brief in briefs:
        assert "email" not in brief


def test_export_includes_email(client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = _login_token(client, "alice", "pw-alice")
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


def test_emailed_code_is_numeric_and_fixed_length(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")
    assert len(code) == RESET_CODE_LENGTH
    assert code.isdigit()


# --- confirm ----------------------------------------------------------------


def test_full_reset_flow_revokes_sessions(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    # Two live sessions.
    old_token = _login_token(client, "alice", "pw-alice")
    client.post("/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"})

    code = _request_code(app, client, "alice@example.com")
    assert _confirm(client, "alice@example.com", code, "brand-new-pw").status_code == 204

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


def test_confirm_tolerates_pasted_whitespace_and_dashes(app: Flask, client: FlaskClient) -> None:
    # Mail clients and humans introduce separators; a code that is right
    # apart from a space must not read as wrong.
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")
    spaced = f" {code[:4]} - {code[4:]} "
    assert _confirm(client, "alice@example.com", spaced, "brand-new-pw").status_code == 204


def test_confirm_email_is_case_insensitive(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")
    assert _confirm(client, "  Alice@Example.COM ", code, "brand-new-pw").status_code == 204


def test_confirm_invalid_code(client: FlaskClient) -> None:
    resp = _confirm(client, "nobody@example.com", "00000000", "brand-new-pw")
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_token"


def test_confirm_missing_code_is_rejected(client: FlaskClient) -> None:
    resp = client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": "alice@example.com", "new_password": "brand-new-pw"},
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_token"


def test_code_does_not_work_against_another_account(app: Flask, client: FlaskClient) -> None:
    # The stored digest is scoped to its owner, so a code minted for one
    # account is meaningless against another even if the digits match.
    _register_with_email(client, "alice", "alice@example.com")
    _register_with_email(client, "bob", "bob@example.com")
    alice_code = _request_code(app, client, "alice@example.com")
    _request_code(app, client, "bob@example.com")

    resp = _confirm(client, "bob@example.com", alice_code, "brand-new-pw")
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_token"
    # Bob's password is untouched.
    assert (
        client.post(
            "/api/v1/auth/login", json={"username": "bob", "password": "pw-bob"}
        ).status_code
        == 200
    )


def test_confirm_weak_password(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")
    resp = _confirm(client, "alice@example.com", code, "short")
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "weak_password"


def test_wrong_code_burns_the_attempt_budget(app: Flask, client: FlaskClient) -> None:
    # The per-code attempt cap — not the IP rate limit — is what makes a
    # short numeric code safe. Once spent, even the right code is dead.
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")
    wrong = "99999999" if code != "99999999" else "11111111"

    max_attempts = app.config["PASSWORD_RESET_MAX_ATTEMPTS"]
    for _ in range(max_attempts):
        assert _confirm(client, "alice@example.com", wrong, "brand-new-pw").status_code == 422

    spent = _confirm(client, "alice@example.com", code, "brand-new-pw")
    assert spent.status_code == 422
    assert spent.get_json()["error"]["code"] == "invalid_token"
    # The original password still stands.
    assert (
        client.post(
            "/api/v1/auth/login", json={"username": "alice", "password": "pw-alice"}
        ).status_code
        == 200
    )


def test_wrong_code_under_the_cap_leaves_the_code_usable(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")
    wrong = "99999999" if code != "99999999" else "11111111"

    assert _confirm(client, "alice@example.com", wrong, "brand-new-pw").status_code == 422
    assert _confirm(client, "alice@example.com", code, "brand-new-pw").status_code == 204


def test_confirm_code_is_single_use(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")

    assert _confirm(client, "alice@example.com", code, "brand-new-pw").status_code == 204
    second = _confirm(client, "alice@example.com", code, "another-new-pw")
    assert second.status_code == 422
    assert second.get_json()["error"]["code"] == "invalid_token"


def test_confirm_expired_code(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    code = _request_code(app, client, "alice@example.com")

    # Force the code past its expiry.
    row = db.session.execute(db.select(PasswordResetToken)).scalar_one()
    row.expires_at = datetime.now(UTC) - timedelta(minutes=1)
    db.session.commit()

    resp = _confirm(client, "alice@example.com", code, "brand-new-pw")
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_token"


def test_new_request_invalidates_prior_code(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    first_code = _request_code(app, client, "alice@example.com")
    second_code = _request_code(app, client, "alice@example.com")
    assert first_code != second_code

    # The first code was superseded.
    assert _confirm(client, "alice@example.com", first_code, "brand-new-pw").status_code == 422
    # The newest code still works.
    assert _confirm(client, "alice@example.com", second_code, "brand-new-pw").status_code == 204


def test_deleted_account_cannot_reset(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = _login_token(client, "alice", "pw-alice")
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
    # And no reset codes linger for the tombstoned user.
    assert db.session.execute(db.select(PasswordResetToken)).first() is None


def test_deletion_clears_email_and_frees_it(app: Flask, client: FlaskClient) -> None:
    _register_with_email(client, "alice", "alice@example.com")
    token = _login_token(client, "alice", "pw-alice")
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
    code = _request_code(app, client, "alice@example.com")
    _confirm(client, "alice@example.com", code, "brand-new-pw")

    new_token = _login_token(client, "alice", "brand-new-pw")
    assert client.get("/api/v1/auth/me", headers=auth_headers(new_token)).status_code == 200
    # No auth tokens remain revoked-then-resurrected oddities: the new one is live.
    live = (
        db.session.execute(db.select(AuthToken).where(AuthToken.revoked_at.is_(None)))
        .scalars()
        .all()
    )
    assert len(live) == 1
