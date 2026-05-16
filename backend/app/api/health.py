"""Health check endpoint."""

from __future__ import annotations

from flask import Blueprint
from sqlalchemy import text

from app.extensions import db

health_bp = Blueprint("health", __name__, url_prefix="/api/v1")


@health_bp.get("/health")
def health():
    try:
        db.session.execute(text("SELECT 1"))
        db_status = "ok"
    except Exception:
        db_status = "error"

    body = {"status": "ok" if db_status == "ok" else "error", "database": db_status}
    return body, (200 if db_status == "ok" else 503)
