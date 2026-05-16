"""Authentication decorators."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
from functools import wraps
from typing import Any

from flask import g, request
from sqlalchemy import select

from app.auth.security import hash_token
from app.errors import error_response
from app.extensions import db
from app.models import AuthToken, User


def _extract_bearer_token() -> str | None:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return None
    raw = header[len("Bearer ") :].strip()
    return raw or None


def login_required(view: Callable[..., Any]) -> Callable[..., Any]:
    """Require a valid, unrevoked bearer token; populate flask.g.current_user."""

    @wraps(view)
    def wrapper(*args: Any, **kwargs: Any):
        raw_token = _extract_bearer_token()
        if raw_token is None:
            return error_response(401, "unauthorized", "Missing or malformed Authorization header.")

        token_hash = hash_token(raw_token)
        token = db.session.execute(
            select(AuthToken).where(
                AuthToken.token_hash == token_hash,
                AuthToken.revoked_at.is_(None),
            )
        ).scalar_one_or_none()

        if token is None:
            return error_response(401, "unauthorized", "Invalid or revoked token.")

        user = db.session.get(User, token.user_id)
        if user is None:
            return error_response(401, "unauthorized", "Token user no longer exists.")

        token.last_used_at = datetime.now(UTC)
        db.session.commit()

        g.current_user = user
        g.current_token = token
        return view(*args, **kwargs)

    return wrapper
