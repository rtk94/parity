"""Internal helpers for shaping ORM rows into API response bodies.

Kept under the ``app.api`` namespace and prefixed with an underscore
because nothing outside the API layer should depend on these
representations directly.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.models import (
    Comment,
    Expense,
    Payment,
    RecurringExpense,
    Relationship,
    User,
)
from app.services.balance import BalanceView


def iso8601_z(dt: datetime | None) -> str | None:
    """Format a datetime as ``YYYY-MM-DDTHH:MM:SSZ`` (UTC, Z suffix)."""
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC).isoformat().replace("+00:00", "Z")


def serialize_user_brief(user: User) -> dict[str, Any]:
    return user.to_public_dict()


def serialize_relationship(rel: Relationship) -> dict[str, Any]:
    return {
        "id": rel.id,
        "inviting_user": serialize_user_brief(rel.inviting_user),
        "invited_user": serialize_user_brief(rel.invited_user),
        "status": rel.status.value,
        "currency_code": rel.currency_code,
        "created_at": iso8601_z(rel.created_at),
    }


def serialize_expense(expense: Expense) -> dict[str, Any]:
    return {
        "id": expense.id,
        "relationship_id": expense.relationship_id,
        "payer_user_id": expense.payer_user_id,
        "total_cents": expense.total_cents,
        "description": expense.description,
        "category": expense.category,
        "created_by_user_id": expense.created_by_user_id,
        "created_at": iso8601_z(expense.created_at),
        "status": expense.status.value,
        "confirmed_at": iso8601_z(expense.confirmed_at),
        "confirmed_by_user_id": expense.confirmed_by_user_id,
        "discarded_at": iso8601_z(expense.discarded_at),
        "discarded_by_user_id": expense.discarded_by_user_id,
        "rejection_reason": expense.rejection_reason,
        "reverses_expense_id": expense.reverses_expense_id,
        "shares": [
            {"user_id": share.user_id, "amount_cents": share.amount_cents}
            for share in expense.shares
        ],
    }


def serialize_payment(payment: Payment) -> dict[str, Any]:
    return {
        "id": payment.id,
        "relationship_id": payment.relationship_id,
        "from_user_id": payment.from_user_id,
        "to_user_id": payment.to_user_id,
        "amount_cents": payment.amount_cents,
        "description": payment.description,
        "created_by_user_id": payment.created_by_user_id,
        "created_at": iso8601_z(payment.created_at),
        "status": payment.status.value,
        "confirmed_at": iso8601_z(payment.confirmed_at),
        "confirmed_by_user_id": payment.confirmed_by_user_id,
        "discarded_at": iso8601_z(payment.discarded_at),
        "discarded_by_user_id": payment.discarded_by_user_id,
        "rejection_reason": payment.rejection_reason,
        "reverses_payment_id": payment.reverses_payment_id,
    }


def serialize_balance_view(bv: BalanceView) -> dict[str, Any]:
    return {
        "net_cents": bv.net_cents,
        "from_user_id": bv.from_user_id,
        "to_user_id": bv.to_user_id,
    }


def serialize_recurring_expense(template: RecurringExpense) -> dict[str, Any]:
    return {
        "id": template.id,
        "relationship_id": template.relationship_id,
        "payer_user_id": template.payer_user_id,
        "total_cents": template.total_cents,
        "description": template.description,
        "category": template.category,
        "interval": template.interval.value,
        "next_run_on": template.next_run_on.isoformat(),
        "active": template.active,
        "created_by_user_id": template.created_by_user_id,
        "created_at": iso8601_z(template.created_at),
        "last_run_at": iso8601_z(template.last_run_at),
        "shares": [
            {"user_id": share.user_id, "amount_cents": share.amount_cents}
            for share in template.shares
        ],
    }


def serialize_comment(comment: Comment) -> dict[str, Any]:
    return {
        "id": comment.id,
        "user_id": comment.user_id,
        "expense_id": comment.expense_id,
        "payment_id": comment.payment_id,
        "content": comment.content,
        "created_at": iso8601_z(comment.created_at),
    }
