"""Payment service: create, list, get, confirm, discard, reverse."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from sqlalchemy import func, or_, select

from app.extensions import db
from app.models import Payment, PaymentStatus, Relationship, RelationshipStatus, User
from app.services import (
    BadRequestError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
    ValidationError,
    notifications,
)
from app.services.audit import log_action


def _stage_payment_against(
    creator: User, rel: Relationship, payload: dict[str, Any] | None
) -> Payment:
    """Validate ``payload`` against ``rel.parties()`` and stage a pending
    payment on the current session.

    Does *not* commit; the caller owns the transaction so it can compose
    with other writes (e.g. the bundled invite + first payment flow). The
    relationship's status is not consulted here — callers enforce whatever
    status policy they require.
    """
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    from_user_id = payload.get("from_user_id")
    to_user_id = payload.get("to_user_id")
    amount_cents = payload.get("amount_cents")
    description = payload.get("description")

    if not isinstance(amount_cents, int) or isinstance(amount_cents, bool) or amount_cents <= 0:
        raise ValidationError("invalid_amount", "amount_cents must be a positive integer.")

    parties = rel.parties()

    for field_name, value in (("from_user_id", from_user_id), ("to_user_id", to_user_id)):
        if not isinstance(value, int) or isinstance(value, bool) or value not in parties:
            raise ValidationError(
                "invalid_party",
                f"{field_name} is not a party to the relationship.",
            )

    if from_user_id == to_user_id:
        raise ValidationError("same_parties", "from_user_id and to_user_id must differ.")

    if description is not None and not isinstance(description, str):
        raise BadRequestError(message="description must be a string if provided.")

    cleaned_description: str | None = None
    if isinstance(description, str) and description.strip():
        cleaned_description = description.strip()

    payment = Payment(
        relationship_id=rel.id,
        from_user_id=from_user_id,
        to_user_id=to_user_id,
        amount_cents=amount_cents,
        description=cleaned_description,
        created_by_user_id=creator.id,
        status=PaymentStatus.pending,
    )
    db.session.add(payment)
    return payment


def create(creator: User, payload: dict[str, Any] | None) -> Payment:
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    relationship_id = payload.get("relationship_id")
    if not isinstance(relationship_id, int) or isinstance(relationship_id, bool):
        raise NotFoundError("relationship_not_found", "Relationship not found.")

    rel = db.session.get(Relationship, relationship_id)
    if rel is None or creator.id not in rel.parties():
        raise NotFoundError("relationship_not_found", "Relationship not found.")
    if rel.status != RelationshipStatus.accepted:
        raise ConflictError("relationship_not_accepted", "Relationship is not accepted.")

    payment = _stage_payment_against(creator, rel, payload)
    db.session.flush()
    log_action(creator.id, "create", "payment", payment.id)
    db.session.commit()
    db.session.refresh(payment)
    notifications.notify_new_payment(payment)
    return payment


def create_for_pending_relationship(
    creator: User, rel: Relationship, payload: dict[str, Any] | None
) -> Payment:
    """Stage a pending payment against a just-created pending relationship.

    Used only by the bundled invite-with-first-payment flow in the
    ``relationships`` service. Does not commit; the bundled flow runs the
    whole sequence (relationship insert + payment) in one transaction so a
    422 on the payment rolls back the relationship.
    """
    return _stage_payment_against(creator, rel, payload)


def list_for_user(
    user: User,
    *,
    relationship_id: int | None,
    status: str | None,
    limit: int,
    offset: int,
) -> tuple[list[Payment], int]:
    visible_rel_ids = select(Relationship.id).where(
        or_(
            Relationship.inviting_user_id == user.id,
            Relationship.invited_user_id == user.id,
        )
    )
    stmt = select(Payment).where(Payment.relationship_id.in_(visible_rel_ids))

    if relationship_id is not None:
        stmt = stmt.where(Payment.relationship_id == relationship_id)

    if status is not None:
        if status not in {s.value for s in PaymentStatus}:
            raise ValidationError("invalid_status", f"Unknown status: {status!r}.")
        stmt = stmt.where(Payment.status == PaymentStatus(status))

    total = db.session.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    stmt = stmt.order_by(Payment.created_at.desc(), Payment.id.desc()).limit(limit).offset(offset)
    items = list(db.session.execute(stmt).scalars().all())
    return items, total


def list_awaiting_confirmation(user: User) -> list[Payment]:
    """Pending payments the counterparty (``user``) must act on.

    Scoped to accepted relationships the user is a party to, restricted to
    entries the *other* party created (a user never confirms their own).
    Reversals are included — they, too, wait on confirmation. Newest first.
    """
    accepted_rel_ids = select(Relationship.id).where(
        Relationship.status == RelationshipStatus.accepted,
        or_(
            Relationship.inviting_user_id == user.id,
            Relationship.invited_user_id == user.id,
        ),
    )
    stmt = (
        select(Payment)
        .where(
            Payment.relationship_id.in_(accepted_rel_ids),
            Payment.status == PaymentStatus.pending,
            Payment.created_by_user_id != user.id,
        )
        .order_by(Payment.created_at.desc(), Payment.id.desc())
    )
    return list(db.session.execute(stmt).scalars().all())


def get_for_user(user: User, payment_id: int) -> Payment:
    payment = db.session.get(Payment, payment_id)
    if payment is None:
        raise NotFoundError("not_found", "Not found.")
    rel = db.session.get(Relationship, payment.relationship_id)
    if rel is None or user.id not in rel.parties():
        raise NotFoundError("not_found", "Not found.")
    return payment


def confirm(user: User, payment_id: int) -> Payment:
    payment = get_for_user(user, payment_id)
    if user.id == payment.created_by_user_id:
        raise ForbiddenError("cannot_self_confirm", "The creator cannot confirm their own entry.")
    if payment.status != PaymentStatus.pending:
        raise ConflictError("payment_not_pending", "Payment is not pending.")

    payment.status = PaymentStatus.confirmed
    payment.confirmed_at = datetime.now(UTC)
    payment.confirmed_by_user_id = user.id
    log_action(user.id, "confirm", "payment", payment.id)
    db.session.commit()
    notifications.notify_payment_confirmed(payment)
    return payment


def discard(user: User, payment_id: int, payload: dict[str, Any] | None) -> Payment:
    payment = get_for_user(user, payment_id)
    if payment.status != PaymentStatus.pending:
        raise ConflictError("payment_not_pending", "Payment is not pending.")

    reason: str | None = None
    if isinstance(payload, dict):
        candidate = payload.get("reason")
        if candidate is not None and not isinstance(candidate, str):
            raise BadRequestError(message="reason must be a string if provided.")
        if isinstance(candidate, str) and candidate.strip():
            reason = candidate.strip()

    payment.status = PaymentStatus.discarded
    payment.discarded_at = datetime.now(UTC)
    payment.discarded_by_user_id = user.id
    payment.rejection_reason = reason
    log_action(user.id, "discard", "payment", payment.id, details=reason)
    db.session.commit()
    return payment


def reverse(user: User, payment_id: int) -> Payment:
    original = get_for_user(user, payment_id)

    if original.status != PaymentStatus.confirmed:
        raise ConflictError("original_not_confirmed", "Original payment is not confirmed.")

    if original.reverses_payment_id is not None:
        raise ConflictError(
            "original_is_reversal",
            "Cannot reverse a reversal; create a fresh entry instead.",
        )

    existing_reversal = db.session.execute(
        select(Payment).where(
            Payment.reverses_payment_id == original.id,
            Payment.status.in_([PaymentStatus.pending, PaymentStatus.confirmed]),
        )
    ).scalar_one_or_none()
    if existing_reversal is not None:
        raise ConflictError(
            "already_reversed",
            "A pending or confirmed reversal already exists for this payment.",
        )

    reversal = Payment(
        relationship_id=original.relationship_id,
        from_user_id=original.from_user_id,
        to_user_id=original.to_user_id,
        amount_cents=original.amount_cents,
        description=f"Reversal of payment #{original.id}",
        created_by_user_id=user.id,
        status=PaymentStatus.pending,
        reverses_payment_id=original.id,
    )
    db.session.add(reversal)
    db.session.flush()
    log_action(
        user.id, "reverse", "payment", reversal.id, details=f"Reversed payment {original.id}"
    )
    db.session.commit()
    db.session.refresh(reversal)
    return reversal
