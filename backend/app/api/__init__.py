"""API subpackage: non-auth endpoints."""

from __future__ import annotations

from app.api.health import health_bp

__all__ = ["health_bp"]
