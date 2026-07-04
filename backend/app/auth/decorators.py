"""Authentication decorators."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from functools import wraps
from typing import Any

from flask import current_app, g, request
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


def _as_utc(dt: datetime) -> datetime:
    # SQLite returns naive datetimes even from ``DateTime(timezone=True)``
    # columns. Treat naive values as UTC (the project invariant) so the
    # comparisons below are timezone-aware on both sides.
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


def login_required(view: Callable[..., Any]) -> Callable[..., Any]:
    """Require a valid, unrevoked, unexpired bearer token; populate flask.g.current_user."""

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

        now = datetime.now(UTC)
        if _as_utc(token.expires_at) <= now:
            return error_response(401, "token_expired", "Token has expired.")

        idle_anchor = _as_utc(token.last_used_at or token.created_at)
        idle_deadline = idle_anchor + timedelta(days=current_app.config["TOKEN_IDLE_LIFETIME_DAYS"])
        if idle_deadline <= now:
            return error_response(401, "token_expired", "Token has expired.")

        token.last_used_at = now
        db.session.commit()

        g.current_user = user
        g.current_token = token
        return view(*args, **kwargs)

    return wrapper


def admin_required(view: Callable[..., Any]) -> Callable[..., Any]:
    """Require ``g.current_user.is_admin``; apply *under* ``login_required``.

    Non-admin callers get 404 rather than 403 so the existence of the
    admin surface is not leaked, mirroring the non-party convention on
    relationship-scoped routes.
    """

    @wraps(view)
    def wrapper(*args: Any, **kwargs: Any):
        user = getattr(g, "current_user", None)
        if user is None or not user.is_admin:
            return error_response(404, "not_found", "Resource not found.")
        return view(*args, **kwargs)

    return wrapper
