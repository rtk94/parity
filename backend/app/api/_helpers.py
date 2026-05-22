"""Internal helpers shared by the Phase 2 API blueprints.

The two helpers here are about how routes talk to the rest of the
stack:

* ``json_body`` pulls a JSON object off the request, returning ``None``
  when the body is missing or not an object. Routes that require a body
  delegate to the service layer, which raises ``BadRequestError`` if it
  gets ``None``.
* ``translates_service_errors`` is a route decorator that converts any
  ``ServiceError`` raised by the service layer into the project's
  standard JSON error envelope.
"""

from __future__ import annotations

from collections.abc import Callable
from functools import wraps
from typing import Any

from flask import request

from app.errors import error_response
from app.services import ServiceError


def json_body() -> dict[str, Any] | None:
    if not request.is_json:
        return None
    data = request.get_json(silent=True)
    return data if isinstance(data, dict) else None


def int_query_arg(name: str) -> int | None:
    """Return a query-string ``int`` arg, or ``None`` if absent.

    Raises ``BadRequestError`` if the value is present but not parseable
    as an integer; the ``translates_service_errors`` decorator turns
    that into a 400 response.
    """
    from app.services import BadRequestError

    raw = request.args.get(name)
    if raw is None or raw == "":
        return None
    try:
        return int(raw)
    except ValueError as exc:
        raise BadRequestError(message=f"{name} must be an integer.") from exc


def translates_service_errors(view: Callable[..., Any]) -> Callable[..., Any]:
    @wraps(view)
    def wrapper(*args: Any, **kwargs: Any):
        try:
            return view(*args, **kwargs)
        except ServiceError as exc:
            return error_response(
                exc.status_code,
                exc.code,
                exc.message,
                exc.details,
            )

    return wrapper
