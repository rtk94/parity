"""Account service: data export and account deletion.

Deleting an account **anonymizes** the user row and revokes their tokens
rather than removing it. The user's expenses, payments, and shares are
part of the counterparty's financial record too, so hard-deleting them
would corrupt the other party's ledger and balances. Anonymizing removes
the personal data (identity, credentials, sessions) while keeping the
shared ledger intact — the counterparty simply sees "Deleted user".
"""

from __future__ import annotations

import secrets
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import delete, or_, select, update

from app.api._serializers import (
    iso8601_z,
    serialize_comment,
    serialize_expense,
    serialize_payment,
    serialize_relationship,
)
from app.auth.security import hash_password
from app.extensions import db
from app.models import (
    AuthToken,
    Comment,
    DeviceToken,
    Expense,
    Payment,
    Relationship,
    User,
)


def export_data(user: User) -> dict[str, Any]:
    """A machine-readable dump of everything tied to this user."""
    relationships = (
        db.session.execute(
            select(Relationship).where(
                or_(
                    Relationship.inviting_user_id == user.id,
                    Relationship.invited_user_id == user.id,
                )
            )
        )
        .scalars()
        .all()
    )
    rel_ids = [r.id for r in relationships]

    expenses = (
        db.session.execute(select(Expense).where(Expense.relationship_id.in_(rel_ids)))
        .scalars()
        .all()
        if rel_ids
        else []
    )
    payments = (
        db.session.execute(select(Payment).where(Payment.relationship_id.in_(rel_ids)))
        .scalars()
        .all()
        if rel_ids
        else []
    )
    comments = db.session.execute(select(Comment).where(Comment.user_id == user.id)).scalars().all()

    return {
        "exported_at": iso8601_z(datetime.now(UTC)),
        "user": {
            "id": user.id,
            "username": user.username,
            "display_name": user.display_name,
            "created_at": iso8601_z(user.created_at),
        },
        "relationships": [serialize_relationship(r) for r in relationships],
        "expenses": [serialize_expense(e) for e in expenses],
        "payments": [serialize_payment(p) for p in payments],
        "comments": [serialize_comment(c) for c in comments],
    }


def delete_account(user: User) -> None:
    """Anonymize the user and revoke their tokens. Ledger rows are kept."""
    now = datetime.now(UTC)
    user.username = f"deleted_user_{user.id}"
    user.display_name = "Deleted user"
    # A fresh random hash so the account can never be logged into again.
    user.password_hash = hash_password(secrets.token_urlsafe(32))
    user.is_admin = False
    user.deleted_at = now
    db.session.execute(
        update(AuthToken)
        .where(AuthToken.user_id == user.id, AuthToken.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    # Push tokens are personal data and useless once the account is dead;
    # remove them outright rather than leave them pointing at a tombstone.
    db.session.execute(delete(DeviceToken).where(DeviceToken.user_id == user.id))
