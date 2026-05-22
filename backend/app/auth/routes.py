"""Authentication routes: register, login, logout, me."""

from __future__ import annotations

from datetime import UTC, datetime

from flask import Blueprint, g, request
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.api._rate_limits import (
    login_ip_limit,
    login_username_limit,
    register_limit,
)
from app.auth.decorators import login_required
from app.auth.security import (
    generate_token,
    hash_password,
    hash_token,
    verify_dummy_password,
    verify_password,
)
from app.errors import error_response
from app.extensions import db
from app.models import AuthToken, User

auth_bp = Blueprint("auth", __name__, url_prefix="/api/v1/auth")


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
    if user is None:
        # Username does not exist. Run argon2 verify against a dummy
        # hash anyway so this branch has the same wall-clock cost as
        # the "wrong password" branch — defeats username enumeration
        # by response-time analysis.
        verify_dummy_password(password)
        return error_response(401, "invalid_credentials", "Invalid username or password.")
    if not verify_password(user.password_hash, password):
        return error_response(401, "invalid_credentials", "Invalid username or password.")

    raw_token = generate_token()
    now = datetime.now(UTC)
    token_row = AuthToken(
        user_id=user.id,
        token_hash=hash_token(raw_token),
        last_used_at=now,
    )
    db.session.add(token_row)
    db.session.commit()

    return {"token": raw_token, "user": user.to_public_dict()}, 200


@auth_bp.post("/logout")
@login_required
def logout():
    token: AuthToken = g.current_token
    token.revoked_at = datetime.now(UTC)
    db.session.commit()
    return "", 204


@auth_bp.get("/me")
@login_required
def me():
    user: User = g.current_user
    return user.to_public_dict(), 200
