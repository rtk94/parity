"""System-administration endpoints. Admin-only; see ``admin_required``."""

from __future__ import annotations

from flask import Blueprint, g, request

from app.auth.decorators import admin_required, login_required
from app.errors import error_response
from app.services import admin as admin_service

admin_bp = Blueprint("admin", __name__, url_prefix="/api/v1/admin")


@admin_bp.get("/stats")
@login_required
@admin_required
def stats():
    return admin_service.ledger_stats(), 200


@admin_bp.post("/cleanup-tokens")
@login_required
@admin_required
def cleanup_tokens():
    deleted = admin_service.cleanup_tokens()
    return {"deleted_tokens": deleted}, 200


@admin_bp.post("/reset-ledger")
@login_required
@admin_required
def reset_ledger():
    data = request.get_json(silent=True)
    confirm = data.get("confirm") if isinstance(data, dict) else None
    if confirm != admin_service.RESET_CONFIRM_PHRASE:
        return error_response(
            422,
            "confirmation_required",
            "Pass the exact confirmation phrase to reset the ledger.",
            details={"expected_confirm": admin_service.RESET_CONFIRM_PHRASE},
        )

    counts = admin_service.reset_ledger(actor_user_id=g.current_user.id)
    return {"deleted": counts}, 200
