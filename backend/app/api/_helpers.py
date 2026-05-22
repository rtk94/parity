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


# Pagination bounds. Documented in backend/README.md; mirrored here so
# the validator and the response builder don't drift.
PAGINATION_DEFAULT_LIMIT = 50
PAGINATION_MAX_LIMIT = 200


def _pagination_error(parameter: str, raw_value: str | None, message: str):
    from app.services import ValidationError

    return ValidationError(
        "invalid_pagination",
        message,
        details={"parameter": parameter, "value": raw_value},
    )


def pagination_args() -> tuple[int, int]:
    """Parse the ``limit`` and ``offset`` query parameters.

    Defaults: ``limit = 50``, ``offset = 0``. Ranges: ``limit ∈ [1,
    200]``, ``offset ≥ 0``. Out-of-range or non-integer values raise
    ``ValidationError`` with code ``invalid_pagination`` and a
    ``{"parameter", "value"}`` detail block identifying which one and
    what was supplied. The route's ``translates_service_errors``
    decorator turns the exception into a 422 response.
    """
    raw_limit = request.args.get("limit")
    if raw_limit is None or raw_limit == "":
        limit = PAGINATION_DEFAULT_LIMIT
    else:
        try:
            limit = int(raw_limit)
        except (TypeError, ValueError) as exc:
            raise _pagination_error(
                "limit", raw_limit, "limit must be an integer in [1, 200]."
            ) from exc
        if limit < 1 or limit > PAGINATION_MAX_LIMIT:
            raise _pagination_error("limit", raw_limit, "limit must be in [1, 200].")

    raw_offset = request.args.get("offset")
    if raw_offset is None or raw_offset == "":
        offset = 0
    else:
        try:
            offset = int(raw_offset)
        except (TypeError, ValueError) as exc:
            raise _pagination_error(
                "offset", raw_offset, "offset must be a non-negative integer."
            ) from exc
        if offset < 0:
            raise _pagination_error("offset", raw_offset, "offset must be a non-negative integer.")

    return limit, offset


def paginated_response(items: list, total: int, limit: int, offset: int) -> dict:
    """Wrap a page of serialised items in the standard pagination envelope."""
    return {
        "items": items,
        "total": total,
        "limit": limit,
        "offset": offset,
        "has_more": offset + len(items) < total,
    }


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
