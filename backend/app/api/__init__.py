"""API subpackage: non-auth endpoints."""

from __future__ import annotations

from app.api.admin import admin_bp
from app.api.expenses import expenses_bp
from app.api.health import health_bp
from app.api.payments import payments_bp
from app.api.relationships import relationships_bp

__all__ = [
    "admin_bp",
    "expenses_bp",
    "health_bp",
    "payments_bp",
    "relationships_bp",
]
