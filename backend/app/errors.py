"""JSON error envelope and Flask error handlers."""

from __future__ import annotations

from typing import Any

from flask import Flask, jsonify
from flask_limiter.errors import RateLimitExceeded
from werkzeug.exceptions import HTTPException


def error_response(
    status: int, short_code: str, message: str, details: dict[str, Any] | None = None
):
    """Build a JSON error response with the project's standard envelope."""
    body: dict[str, Any] = {
        "error": {
            "code": short_code,
            "message": message,
        }
    }
    if details:
        body["error"]["details"] = details
    return jsonify(body), status


_DEFAULT_SHORT_CODES: dict[int, str] = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    409: "conflict",
    422: "unprocessable_entity",
    500: "internal_server_error",
}


def register_error_handlers(app: Flask) -> None:
    """Register JSON error handlers for the documented status codes."""

    def _http_handler(exc: HTTPException):
        code = exc.code or 500
        short = _DEFAULT_SHORT_CODES.get(code, "error")
        return error_response(code, short, exc.description or short)

    for status in _DEFAULT_SHORT_CODES:
        app.register_error_handler(status, _http_handler)

    @app.errorhandler(RateLimitExceeded)
    def _rate_limited(exc: RateLimitExceeded):
        # ``exc.description`` is set to ``str(limit.limit)`` by
        # ``RateLimitExceeded.__init__`` — e.g. ``"5 per 1 minute"`` —
        # which is exactly what we want to surface to clients in the
        # error envelope. Flask-Limiter's ``after_request`` hook adds
        # the ``Retry-After`` and ``X-RateLimit-*`` headers (because
        # the limiter is constructed with ``headers_enabled=True``).
        return error_response(
            429,
            "rate_limited",
            "Rate limit exceeded. Try again later.",
            details={"limit": str(exc.description)},
        )

    @app.errorhandler(Exception)
    def _unhandled(exc: Exception):
        if isinstance(exc, HTTPException):
            return _http_handler(exc)
        app.logger.exception("Unhandled exception: %s", exc)
        return error_response(500, "internal_server_error", "Internal server error.")
