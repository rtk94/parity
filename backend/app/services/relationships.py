"""Relationship service: invite, accept, reject, list, get."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from sqlalchemy import and_, func, or_, select
from sqlalchemy.exc import IntegrityError

from app.extensions import db
from app.models import Expense, ExpenseStatus, Relationship, RelationshipStatus, User
from app.services import (
    BadRequestError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
    ServiceError,
    ValidationError,
)

# Constant rejection reason stamped on pending expenses that get
# cascade-discarded when their relationship is rejected. Surfaced to
# the client via the expense's ``rejection_reason`` field so a UI can
# distinguish a manual discard from a relationship-driven one.
_CASCADE_REJECT_REASON = "Relationship rejected"


def invite_by_username(
    inviter: User, payload: dict[str, Any] | None
) -> tuple[Relationship, Expense | None]:
    """Create a pending relationship; optionally create a pending first expense.

    Returns ``(relationship, expense)``. ``expense`` is ``None`` when
    the payload omits ``first_expense``, in which case the route emits
    the Phase 2 single-resource response shape. When ``first_expense``
    is present, the relationship insert and the expense + share inserts
    happen in a single transaction; any validation failure on the
    first expense rolls back the relationship.
    """
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    username = payload.get("username")
    if not isinstance(username, str) or not username.strip():
        raise BadRequestError(message="username is required.")

    invitee = db.session.execute(
        select(User).where(User.username == username.strip())
    ).scalar_one_or_none()
    if invitee is None:
        raise NotFoundError("user_not_found", "No user with that username.")

    if invitee.id == inviter.id:
        raise ValidationError("cannot_invite_self", "You cannot invite yourself.")

    if _existing_active_relationship(inviter.id, invitee.id) is not None:
        raise ConflictError(
            "relationship_exists",
            "A relationship already exists between these users.",
        )

    first_expense_payload = payload.get("first_expense")

    rel = Relationship(
        inviting_user_id=inviter.id,
        invited_user_id=invitee.id,
        status=RelationshipStatus.pending,
    )
    db.session.add(rel)

    if first_expense_payload is None:
        try:
            db.session.commit()
        except IntegrityError:
            db.session.rollback()
            raise ConflictError(
                "relationship_exists",
                "A relationship already exists between these users.",
            ) from None
        return rel, None

    # Bundled flow: flush the relationship to obtain its id, then stage
    # the expense + shares against it. Any service error in the expense
    # validation rolls back the entire transaction so the relationship
    # is never persisted.
    try:
        db.session.flush()
    except IntegrityError:
        db.session.rollback()
        raise ConflictError(
            "relationship_exists",
            "A relationship already exists between these users.",
        ) from None

    from app.services import expenses as expenses_service

    try:
        expense = expenses_service.create_for_pending_relationship(
            inviter, rel, first_expense_payload
        )
        db.session.commit()
    except ServiceError:
        db.session.rollback()
        raise

    db.session.refresh(expense)
    return rel, expense


def list_for_user(
    user: User,
    *,
    status: str | None,
    limit: int,
    offset: int,
) -> tuple[list[Relationship], int]:
    stmt = select(Relationship).where(
        or_(
            Relationship.inviting_user_id == user.id,
            Relationship.invited_user_id == user.id,
        )
    )
    if status is not None:
        if status not in {s.value for s in RelationshipStatus}:
            raise ValidationError("invalid_status", f"Unknown status: {status!r}.")
        stmt = stmt.where(Relationship.status == RelationshipStatus(status))

    total = db.session.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    stmt = (
        stmt.order_by(Relationship.created_at.desc(), Relationship.id.desc())
        .limit(limit)
        .offset(offset)
    )
    items = list(db.session.execute(stmt).scalars().all())
    return items, total


def get_for_user(user: User, relationship_id: int) -> Relationship:
    """Return the relationship if the caller is a party; otherwise 404."""
    rel = db.session.get(Relationship, relationship_id)
    if rel is None or user.id not in rel.parties():
        raise NotFoundError("not_found", "Not found.")
    return rel


def accept(user: User, relationship_id: int) -> Relationship:
    rel = get_for_user(user, relationship_id)
    if user.id != rel.invited_user_id:
        raise ForbiddenError(
            "not_invited_party",
            "Only the invited user can accept this relationship.",
        )
    if rel.status != RelationshipStatus.pending:
        raise ConflictError(
            "relationship_not_pending",
            "Relationship is not pending.",
        )
    rel.status = RelationshipStatus.accepted
    db.session.commit()
    return rel


def reject(user: User, relationship_id: int) -> Relationship:
    rel = get_for_user(user, relationship_id)
    if rel.status != RelationshipStatus.pending:
        raise ConflictError(
            "relationship_not_pending",
            "Relationship is not pending.",
        )

    now = datetime.now(UTC)
    rel.status = RelationshipStatus.rejected

    # Cascade-discard any pending expenses on this relationship. Phase 2
    # forbids creating payments against non-accepted relationships, so
    # only pending expenses can exist here; confirmed expenses cannot
    # exist on a pending relationship, and discarded entries are
    # terminal already.
    pending_expenses = (
        db.session.execute(
            select(Expense).where(
                Expense.relationship_id == rel.id,
                Expense.status == ExpenseStatus.pending,
            )
        )
        .scalars()
        .all()
    )
    for expense in pending_expenses:
        expense.status = ExpenseStatus.discarded
        expense.discarded_at = now
        expense.discarded_by_user_id = user.id
        expense.rejection_reason = _CASCADE_REJECT_REASON

    db.session.commit()
    return rel


def _existing_active_relationship(user_a: int, user_b: int) -> Relationship | None:
    """Return any non-rejected relationship between the pair (either direction)."""
    return db.session.execute(
        select(Relationship).where(
            Relationship.status != RelationshipStatus.rejected,
            or_(
                and_(
                    Relationship.inviting_user_id == user_a,
                    Relationship.invited_user_id == user_b,
                ),
                and_(
                    Relationship.inviting_user_id == user_b,
                    Relationship.invited_user_id == user_a,
                ),
            ),
        )
    ).scalar_one_or_none()
