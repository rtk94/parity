"""Expense service: create, list, get, confirm, discard, reverse."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from sqlalchemy import func, or_, select

from app.extensions import db
from app.models import (
    Expense,
    ExpenseShare,
    ExpenseStatus,
    Relationship,
    RelationshipStatus,
    User,
)
from app.services import (
    BadRequestError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
    ValidationError,
)
from app.services.audit import log_action


def _stage_expense_against(
    creator: User, rel: Relationship, payload: dict[str, Any] | None
) -> Expense:
    """Validate ``payload`` against ``rel.parties()`` and stage a pending
    expense plus its shares on the current session.

    Does *not* commit; the caller is responsible for the transaction so
    it can compose with other writes (e.g. the bundled invite + first
    expense flow). The relationship's status is not consulted here — the
    callers enforce whatever status policy they require.
    """
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    payer_id = payload.get("payer_user_id")
    total_cents = payload.get("total_cents")
    description = payload.get("description")
    category = payload.get("category")
    shares = payload.get("shares")

    if not isinstance(total_cents, int) or isinstance(total_cents, bool) or total_cents <= 0:
        raise ValidationError("invalid_amount", "total_cents must be a positive integer.")

    if not isinstance(description, str) or not description.strip():
        raise ValidationError("empty_description", "description is required.")

    if not isinstance(shares, list) or len(shares) == 0:
        raise ValidationError("empty_shares", "shares must be a non-empty list.")

    parties = rel.parties()

    if not isinstance(payer_id, int) or isinstance(payer_id, bool) or payer_id not in parties:
        raise ValidationError("invalid_payer", "payer_user_id is not a party to the relationship.")

    seen_users: set[int] = set()
    shares_sum = 0
    cleaned_shares: list[tuple[int, int]] = []
    for share in shares:
        if not isinstance(share, dict):
            raise BadRequestError(message="Each share must be an object.")
        share_user_id = share.get("user_id")
        share_amount = share.get("amount_cents")

        if not isinstance(share_amount, int) or isinstance(share_amount, bool) or share_amount <= 0:
            raise ValidationError(
                "invalid_amount", "share amount_cents must be a positive integer."
            )

        if not isinstance(share_user_id, int) or isinstance(share_user_id, bool):
            raise ValidationError(
                "invalid_share_user", "share user_id is not a party to the relationship."
            )

        if share_user_id in seen_users:
            raise ValidationError(
                "duplicate_share_user",
                f"user {share_user_id} appears in shares more than once.",
            )
        seen_users.add(share_user_id)

        if share_user_id not in parties:
            raise ValidationError(
                "invalid_share_user",
                f"user {share_user_id} is not a party to the relationship.",
            )

        shares_sum += share_amount
        cleaned_shares.append((share_user_id, share_amount))

    if shares_sum != total_cents:
        raise ValidationError(
            "share_sum_mismatch",
            "Sum of share amounts does not equal total_cents.",
            details={"total_cents": total_cents, "shares_sum_cents": shares_sum},
        )

    if category is not None and (not isinstance(category, str) or len(category.strip()) > 64):
        raise ValidationError("invalid_category", "category must be a string under 64 characters.")

    expense = Expense(
        relationship_id=rel.id,
        payer_user_id=payer_id,
        total_cents=total_cents,
        description=description.strip(),
        category=category.strip() if isinstance(category, str) and category.strip() else None,
        created_by_user_id=creator.id,
        status=ExpenseStatus.pending,
    )
    db.session.add(expense)
    db.session.flush()

    for user_id, amount_cents in cleaned_shares:
        db.session.add(
            ExpenseShare(
                expense_id=expense.id,
                user_id=user_id,
                amount_cents=amount_cents,
            )
        )

    return expense


def create(creator: User, payload: dict[str, Any] | None) -> Expense:
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    relationship_id = payload.get("relationship_id")
    if not isinstance(relationship_id, int) or isinstance(relationship_id, bool):
        raise NotFoundError("relationship_not_found", "Relationship not found.")

    rel = db.session.get(Relationship, relationship_id)
    if rel is None or creator.id not in rel.parties():
        raise NotFoundError("relationship_not_found", "Relationship not found.")
    if rel.status != RelationshipStatus.accepted:
        raise ConflictError(
            "relationship_not_accepted",
            "Relationship is not accepted.",
        )

    expense = _stage_expense_against(creator, rel, payload)
    log_action(creator.id, "create", "expense", expense.id)
    db.session.commit()
    db.session.refresh(expense)
    return expense


def create_for_pending_relationship(
    creator: User, rel: Relationship, payload: dict[str, Any] | None
) -> Expense:
    """Stage a pending expense against a just-created pending relationship.

    Used only by the bundled invite-with-first-expense flow in
    ``relationships`` service. Does not commit; the bundled flow runs
    the whole sequence (relationship insert + expense + shares) in one
    transaction so a 422 on the expense rolls back the relationship.
    """
    return _stage_expense_against(creator, rel, payload)


def list_for_user(
    user: User,
    *,
    relationship_id: int | None,
    status: str | None,
    limit: int,
    offset: int,
) -> tuple[list[Expense], int]:
    visible_rel_ids = select(Relationship.id).where(
        or_(
            Relationship.inviting_user_id == user.id,
            Relationship.invited_user_id == user.id,
        )
    )
    stmt = select(Expense).where(Expense.relationship_id.in_(visible_rel_ids))

    if relationship_id is not None:
        stmt = stmt.where(Expense.relationship_id == relationship_id)

    if status is not None:
        if status not in {s.value for s in ExpenseStatus}:
            raise ValidationError("invalid_status", f"Unknown status: {status!r}.")
        stmt = stmt.where(Expense.status == ExpenseStatus(status))

    total = db.session.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    stmt = stmt.order_by(Expense.created_at.desc(), Expense.id.desc()).limit(limit).offset(offset)
    items = list(db.session.execute(stmt).scalars().all())
    return items, total


def get_for_user(user: User, expense_id: int) -> Expense:
    expense = db.session.get(Expense, expense_id)
    if expense is None:
        raise NotFoundError("not_found", "Not found.")
    rel = db.session.get(Relationship, expense.relationship_id)
    if rel is None or user.id not in rel.parties():
        raise NotFoundError("not_found", "Not found.")
    return expense


def confirm(user: User, expense_id: int) -> Expense:
    expense = get_for_user(user, expense_id)
    if user.id == expense.created_by_user_id:
        raise ForbiddenError(
            "cannot_self_confirm",
            "The creator cannot confirm their own entry.",
        )
    if expense.status != ExpenseStatus.pending:
        raise ConflictError("expense_not_pending", "Expense is not pending.")

    expense.status = ExpenseStatus.confirmed
    expense.confirmed_at = datetime.now(UTC)
    expense.confirmed_by_user_id = user.id
    log_action(user.id, "confirm", "expense", expense.id)
    db.session.commit()
    return expense


def discard(user: User, expense_id: int, payload: dict[str, Any] | None) -> Expense:
    expense = get_for_user(user, expense_id)
    if expense.status != ExpenseStatus.pending:
        raise ConflictError("expense_not_pending", "Expense is not pending.")

    reason: str | None = None
    if isinstance(payload, dict):
        candidate = payload.get("reason")
        if candidate is not None and not isinstance(candidate, str):
            raise BadRequestError(message="reason must be a string if provided.")
        if isinstance(candidate, str) and candidate.strip():
            reason = candidate.strip()

    expense.status = ExpenseStatus.discarded
    expense.discarded_at = datetime.now(UTC)
    expense.discarded_by_user_id = user.id
    expense.rejection_reason = reason
    log_action(user.id, "discard", "expense", expense.id, details=reason)
    db.session.commit()
    return expense


def reverse(user: User, expense_id: int) -> Expense:
    original = get_for_user(user, expense_id)

    if original.status != ExpenseStatus.confirmed:
        raise ConflictError("original_not_confirmed", "Original expense is not confirmed.")

    if original.reverses_expense_id is not None:
        raise ConflictError(
            "original_is_reversal",
            "Cannot reverse a reversal; create a fresh entry instead.",
        )

    existing_reversal = db.session.execute(
        select(Expense).where(
            Expense.reverses_expense_id == original.id,
            Expense.status.in_([ExpenseStatus.pending, ExpenseStatus.confirmed]),
        )
    ).scalar_one_or_none()
    if existing_reversal is not None:
        raise ConflictError(
            "already_reversed",
            "A pending or confirmed reversal already exists for this expense.",
        )

    reversal = Expense(
        relationship_id=original.relationship_id,
        payer_user_id=original.payer_user_id,
        total_cents=original.total_cents,
        description=f"Reversal of expense #{original.id}",
        created_by_user_id=user.id,
        status=ExpenseStatus.pending,
        reverses_expense_id=original.id,
    )
    db.session.add(reversal)
    db.session.flush()

    for share in original.shares:
        db.session.add(
            ExpenseShare(
                expense_id=reversal.id,
                user_id=share.user_id,
                amount_cents=share.amount_cents,
            )
        )

    log_action(user.id, "reverse", "expense", reversal.id, details=f"Reversed expense {original.id}")
    db.session.commit()
    db.session.refresh(reversal)
    return reversal
