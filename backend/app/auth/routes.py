"""Authentication routes: register, login, logout, me."""

from __future__ import annotations

from datetime import UTC, datetime

from flask import Blueprint, g, request
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.api._helpers import translates_service_errors
from app.api._rate_limits import (
    change_password_limit,
    login_ip_limit,
    login_username_limit,
    refresh_limit,
    register_limit,
    update_profile_limit,
)
from app.auth.decorators import login_required
from app.auth.security import (
    generate_token,
    hash_password,
    hash_token,
    token_absolute_expiry,
    verify_dummy_password,
    verify_password,
)
from app.errors import error_response
from app.extensions import db
from app.models import AuthToken, User
from app.services import account as account_service
from app.services import devices as device_service

auth_bp = Blueprint("auth", __name__, url_prefix="/api/v1/auth")


def _issue_token_for(user: User) -> tuple[str, AuthToken]:
    """Create and stage a fresh ``AuthToken`` row for ``user``.

    Returns the raw token (for the response body) and the staged
    ``AuthToken`` ORM instance. The caller is responsible for
    ``db.session.commit()``.
    """
    raw_token = generate_token()
    now = datetime.now(UTC)
    token_row = AuthToken(
        user_id=user.id,
        token_hash=hash_token(raw_token),
        last_used_at=now,
        expires_at=token_absolute_expiry(now),
    )
    db.session.add(token_row)
    return raw_token, token_row


def _json_body() -> dict | None:
    if not request.is_json:
        return None
    data = request.get_json(silent=True)
    return data if isinstance(data, dict) else None


@auth_bp.post("/register")
@register_limit()
def register():
    data = _json_body()
    if data is None:
        return error_response(400, "bad_request", "JSON body required.")

    username = data.get("username")
    password = data.get("password")
    display_name = data.get("display_name")

    if not isinstance(username, str) or not username.strip():
        return error_response(422, "invalid_username", "username is required.")
    if not isinstance(password, str) or len(password) < 1:
        return error_response(422, "invalid_password", "password is required.")
    if not isinstance(display_name, str) or not display_name.strip():
        return error_response(422, "invalid_display_name", "display_name is required.")

    username = username.strip()
    display_name = display_name.strip()

    user = User(
        username=username,
        password_hash=hash_password(password),
        display_name=display_name,
    )
    db.session.add(user)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return error_response(409, "username_taken", "Username is already taken.")

    return user.to_public_dict(), 201


@auth_bp.post("/login")
@login_ip_limit()
@login_username_limit()
def login():
    data = _json_body()
    if data is None:
        return error_response(400, "bad_request", "JSON body required.")

    username = data.get("username")
    password = data.get("password")
    if not isinstance(username, str) or not isinstance(password, str):
        return error_response(401, "invalid_credentials", "Invalid username or password.")

    user = db.session.execute(select(User).where(User.username == username)).scalar_one_or_none()
    if user is None or user.is_deleted:
        # Unknown or deleted account. Run argon2 verify against a dummy
        # hash anyway so this branch has the same wall-clock cost as
        # the "wrong password" branch — defeats username enumeration
        # by response-time analysis.
        verify_dummy_password(password)
        return error_response(401, "invalid_credentials", "Invalid username or password.")
    if not verify_password(user.password_hash, password):
        return error_response(401, "invalid_credentials", "Invalid username or password.")

    raw_token, _ = _issue_token_for(user)
    db.session.commit()

    return {"token": raw_token, "user": user.to_public_dict()}, 200


@auth_bp.post("/logout")
@login_required
def logout():
    token: AuthToken = g.current_token
    token.revoked_at = datetime.now(UTC)
    db.session.commit()
    return "", 204


@auth_bp.post("/devices")
@login_required
@translates_service_errors
def register_device():
    """Register this device's push token for the caller."""
    data = _json_body()
    if data is None:
        return error_response(400, "bad_request", "JSON body required.")
    device = device_service.register_device(
        g.current_user,
        token=data.get("token"),
        platform=data.get("platform", "android"),
    )
    return device.to_dict(), 200


@auth_bp.delete("/devices")
@login_required
@translates_service_errors
def unregister_device():
    """Remove this device's push token (called on logout)."""
    data = _json_body()
    if data is None:
        return error_response(400, "bad_request", "JSON body required.")
    device_service.unregister_device(g.current_user, token=data.get("token"))
    return "", 204


@auth_bp.get("/me")
@login_required
def me():
    user: User = g.current_user
    return user.to_private_dict(), 200


@auth_bp.patch("/me")
@login_required
@update_profile_limit()
def update_profile():
    data = _json_body()
    if data is None:
        return error_response(400, "bad_request", "JSON body required.")

    display_name = data.get("display_name")
    if display_name is not None:
        if not isinstance(display_name, str) or not display_name.strip():
            return error_response(422, "invalid_display_name", "display_name cannot be empty.")
        g.current_user.display_name = display_name.strip()

    db.session.commit()
    return g.current_user.to_public_dict(), 200


@auth_bp.get("/me/export")
@login_required
def export_account():
    """Machine-readable dump of everything tied to the caller's account."""
    return account_service.export_data(g.current_user), 200


@auth_bp.delete("/me")
@login_required
def delete_account():
    """Delete (anonymize) the caller's account. Requires the password.

    The account row is retained but anonymized and its tokens revoked, so
    the counterparty's shared ledger stays intact. This cannot be undone.
    """
    data = _json_body()
    password = data.get("password") if isinstance(data, dict) else None
    if not isinstance(password, str) or not verify_password(g.current_user.password_hash, password):
        return error_response(
            403, "invalid_password", "Password confirmation is required to delete your account."
        )
    account_service.delete_account(g.current_user)
    db.session.commit()
    return "", 204


# Minimum password length enforced on ``change-password``. Matches the
# value surfaced in the ``weak_password`` error detail. Kept as a
# module-level constant so the route and the test reference the same
# number.
_MIN_PASSWORD_LENGTH = 8


@auth_bp.post("/change-password")
@login_required
@change_password_limit()
def change_password():
    data = _json_body()
    if data is None:
        return error_response(400, "bad_request", "JSON body required.")

    current_password = data.get("current_password")
    new_password = data.get("new_password")
    if not isinstance(current_password, str) or not isinstance(new_password, str):
        return error_response(
            422,
            "invalid_request",
            "current_password and new_password are required.",
        )

    if len(new_password) < _MIN_PASSWORD_LENGTH:
        return error_response(
            422,
            "weak_password",
            "new_password is too short.",
            details={"min_length": _MIN_PASSWORD_LENGTH},
        )

    user: User = g.current_user
    if not verify_password(user.password_hash, current_password):
        return error_response(422, "invalid_current_password", "current_password is incorrect.")

    current_token: AuthToken = g.current_token
    user.password_hash = hash_password(new_password)
    db.session.execute(
        AuthToken.__table__.update()
        .where(
            AuthToken.user_id == user.id,
            AuthToken.id != current_token.id,
            AuthToken.revoked_at.is_(None),
        )
        .values(revoked_at=datetime.now(UTC))
    )
    db.session.commit()
    return "", 204


@auth_bp.post("/refresh")
@login_required
@refresh_limit()
def refresh():
    user: User = g.current_user
    old_token: AuthToken = g.current_token

    raw_token, _ = _issue_token_for(user)
    old_token.revoked_at = datetime.now(UTC)
    db.session.commit()

    return {"token": raw_token, "user": user.to_public_dict()}, 200
