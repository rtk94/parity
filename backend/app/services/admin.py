"""System-administration services: ledger reset, stats, token cleanup.

These are the only code paths allowed to delete ledger rows. The
DB-level immutability triggers exist precisely to stop deletes, so
``reset_ledger`` drops the three delete-guard triggers, clears the
ledger tables, and reinstalls the guards — all inside one transaction
so a failure at any point leaves the database untouched.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import func, select, text

from app.extensions import db
from app.models import (
    AuditLog,
    AuthToken,
    Comment,
    Expense,
    ExpenseShare,
    Payment,
    Relationship,
    User,
)
from app.models._triggers import TRIGGER_STATEMENTS
from app.services.audit import log_action

# The confirmation phrase the API requires before resetting. Kept as a
# module-level constant so the route, the app client, and the tests
# reference the same string.
RESET_CONFIRM_PHRASE = "RESET LEDGER"

_DELETE_GUARD_TRIGGERS = (
    "trg_expense_no_delete",
    "trg_expense_share_no_delete",
    "trg_payment_no_delete",
)


def _delete_guard_ddl() -> list[str]:
    """The CREATE TRIGGER statements for the delete guards, verbatim
    from the canonical trigger module."""
    statements = [
        stmt for stmt in TRIGGER_STATEMENTS if any(name in stmt for name in _DELETE_GUARD_TRIGGERS)
    ]
    assert len(statements) == len(_DELETE_GUARD_TRIGGERS)
    return statements


def reset_ledger(actor_user_id: int | None = None) -> dict[str, int]:
    """Erase every ledger entry (expenses, shares, payments, comments).

    Users, relationships, auth tokens, and the audit log are kept.
    Returns the number of rows deleted per table. When
    ``actor_user_id`` is provided the reset itself is written to the
    audit log; the CLI path passes None when no admin user exists yet.
    """
    counts = {
        "expenses": db.session.scalar(select(func.count()).select_from(Expense)) or 0,
        "expense_shares": db.session.scalar(select(func.count()).select_from(ExpenseShare)) or 0,
        "payments": db.session.scalar(select(func.count()).select_from(Payment)) or 0,
        "comments": db.session.scalar(select(func.count()).select_from(Comment)) or 0,
    }

    for name in _DELETE_GUARD_TRIGGERS:
        db.session.execute(text(f"DROP TRIGGER {name}"))

    # Children before parents: comments reference entries, shares
    # reference expenses.
    db.session.execute(text("DELETE FROM comment"))
    db.session.execute(text("DELETE FROM expense_share"))
    db.session.execute(text("DELETE FROM expense"))
    db.session.execute(text("DELETE FROM payment"))

    for stmt in _delete_guard_ddl():
        db.session.execute(text(stmt))

    if actor_user_id is not None:
        log_action(
            actor_user_id,
            "reset",
            "ledger",
            0,
            details=(
                f"Erased {counts['expenses']} expenses, {counts['payments']} payments, "
                f"{counts['comments']} comments"
            ),
        )

    db.session.commit()
    return counts


def ledger_stats() -> dict[str, int]:
    """Row counts an administrator cares about."""
    now = datetime.now(UTC)

    def count(model) -> int:
        return db.session.scalar(select(func.count()).select_from(model)) or 0

    active_tokens = (
        db.session.scalar(
            select(func.count())
            .select_from(AuthToken)
            .where(AuthToken.revoked_at.is_(None), AuthToken.expires_at > now)
        )
        or 0
    )
    return {
        "users": count(User),
        "relationships": count(Relationship),
        "expenses": count(Expense),
        "payments": count(Payment),
        "comments": count(Comment),
        "active_tokens": active_tokens,
        "audit_entries": count(AuditLog),
    }


def cleanup_tokens() -> int:
    """Delete expired and revoked auth tokens; returns the count."""
    now = datetime.now(UTC)
    deleted = (
        db.session.query(AuthToken)
        .filter((AuthToken.expires_at < now) | (AuthToken.revoked_at.isnot(None)))
        .delete()
    )
    db.session.commit()
    return deleted
