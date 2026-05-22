"""Rate-limit helpers shared by the Phase 3 endpoints.

Two pieces live here:

* ``authed_write_limit`` is the decorator factory applied to every
  authenticated ``POST`` endpoint under ``relationships``, ``expenses``,
  and ``payments``. Reading the limit string from ``current_app.config``
  lets ops tune the value without touching code; keying by
  ``g.current_user.id`` keeps the limit per-user. ``@login_required``
  must wrap the view *outside* the limiter (see the project spec for
  Phase 3) so ``g.current_user`` is set before the key function runs.
* ``login_username_key_func`` extracts the ``username`` field from a
  login request body for the per-username login limit. Returning
  ``None`` causes Flask-Limiter to skip that limit for the request —
  used when the body is missing, unparseable, or lacks a usable
  username; the per-IP limit still applies in those cases.
"""

from __future__ import annotations

from flask import current_app, g, request

from app.extensions import limiter


def authed_write_limit():
    """Return the per-authenticated-user write limit decorator."""
    return limiter.limit(
        lambda: current_app.config["RATELIMIT_WRITE"],
        key_func=lambda: f"user:{g.current_user.id}",
    )


def change_password_limit():
    """Return the per-authenticated-user change-password limit decorator.

    Distinct from the generic write limit so a tighter cap can be set
    on credential mutation; a stolen token must not be a free
    brute-force gun against the user's current password.
    """
    return limiter.limit(
        lambda: current_app.config["RATELIMIT_CHANGE_PASSWORD"],
        key_func=lambda: f"user:{g.current_user.id}",
    )


def refresh_limit():
    """Return the per-authenticated-user token-refresh limit decorator."""
    return limiter.limit(
        lambda: current_app.config["RATELIMIT_REFRESH"],
        key_func=lambda: f"user:{g.current_user.id}",
    )


def login_ip_limit():
    """Return the per-IP login limit decorator."""
    return limiter.limit(lambda: current_app.config["RATELIMIT_LOGIN_IP"])


def login_username_limit():
    """Return the per-username login limit decorator.

    The username is read from the JSON body via ``login_username_key_func``;
    if no usable username can be extracted (missing body, wrong type,
    blank string) the key function returns ``None`` and Flask-Limiter
    skips this limit. The companion ``login_ip_limit`` still applies.
    """
    return limiter.limit(
        lambda: current_app.config["RATELIMIT_LOGIN_USERNAME"],
        key_func=login_username_key_func,
    )


def register_limit():
    """Return the per-IP register limit decorator."""
    return limiter.limit(lambda: current_app.config["RATELIMIT_REGISTER"])


def login_username_key_func() -> str | None:
    if not request.is_json:
        return None
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return None
    username = data.get("username")
    if not isinstance(username, str) or not username.strip():
        return None
    return f"login_username:{username.strip()}"
