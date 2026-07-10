"""Pending endpoint: entries across all relationships awaiting the
caller's confirmation, for the dashboard "needs your confirmation" view."""

from __future__ import annotations

from flask import Blueprint, g

from app.api._helpers import translates_service_errors
from app.api._serializers import serialize_expense, serialize_payment
from app.auth.decorators import login_required
from app.services import expenses as expenses_service
from app.services import payments as payments_service

pending_bp = Blueprint("pending", __name__, url_prefix="/api/v1/pending")


@pending_bp.get("")
@login_required
@translates_service_errors
def list_pending():
    """Expenses and payments the caller must confirm, newest first.

    Returned as two lists mirroring the ledger's separate endpoints; the
    client merges them for display.
    """
    expenses = expenses_service.list_awaiting_confirmation(g.current_user)
    payments = payments_service.list_awaiting_confirmation(g.current_user)
    return {
        "expenses": [serialize_expense(e) for e in expenses],
        "payments": [serialize_payment(p) for p in payments],
    }, 200
