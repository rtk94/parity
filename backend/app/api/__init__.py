"""API subpackage: non-auth endpoints."""

from __future__ import annotations

from app.api.admin import admin_bp
from app.api.attachments import attachments_bp
from app.api.expenses import expenses_bp
from app.api.health import health_bp
from app.api.payments import payments_bp
from app.api.pending import pending_bp
from app.api.recurring import recurring_bp
from app.api.relationships import relationships_bp

__all__ = [
    "admin_bp",
    "attachments_bp",
    "expenses_bp",
    "health_bp",
    "payments_bp",
    "pending_bp",
    "recurring_bp",
    "relationships_bp",
]
