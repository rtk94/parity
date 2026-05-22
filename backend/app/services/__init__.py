"""Service layer for Parity.

Service functions encapsulate the validation and mutation logic that
drives the API. Routes parse incoming requests and authorise callers,
then delegate to a service function which performs the actual work and
raises a ``ServiceError`` (mapped back to the standard JSON error
envelope by the route).

Centralising the exception classes here means each service module can
import them with ``from app.services import NotFoundError`` etc., and
the routes can catch the common base ``ServiceError``.
"""

from __future__ import annotations

from typing import Any


class ServiceError(Exception):
    """Base class for service-layer errors that map onto HTTP responses."""

    status_code: int = 400
    default_code: str = "bad_request"

    def __init__(
        self,
        code: str | None = None,
        message: str = "",
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message or (code or self.default_code))
        self.code = code or self.default_code
        self.message = message or self.code
        self.details = details


class BadRequestError(ServiceError):
    status_code = 400
    default_code = "bad_request"


class ForbiddenError(ServiceError):
    status_code = 403
    default_code = "forbidden"


class NotFoundError(ServiceError):
    status_code = 404
    default_code = "not_found"


class ConflictError(ServiceError):
    status_code = 409
    default_code = "conflict"


class ValidationError(ServiceError):
    status_code = 422
    default_code = "unprocessable_entity"


__all__ = [
    "BadRequestError",
    "ConflictError",
    "ForbiddenError",
    "NotFoundError",
    "ServiceError",
    "ValidationError",
]
