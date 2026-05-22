"""Relationship service: invite, accept, reject, list, get."""

from __future__ import annotations

from typing import Any

from sqlalchemy import and_, or_, select
from sqlalchemy.exc import IntegrityError

from app.extensions import db
from app.models import Relationship, RelationshipStatus, User
from app.services import (
    BadRequestError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
    ValidationError,
)


def invite_by_username(inviter: User, payload: dict[str, Any] | None) -> Relationship:
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

    rel = Relationship(
        inviting_user_id=inviter.id,
        invited_user_id=invitee.id,
        status=RelationshipStatus.pending,
    )
    db.session.add(rel)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        raise ConflictError(
            "relationship_exists",
            "A relationship already exists between these users.",
        ) from None
    return rel


def list_for_user(user: User, *, status: str | None) -> list[Relationship]:
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
    stmt = stmt.order_by(Relationship.created_at.desc(), Relationship.id.desc())
    return list(db.session.execute(stmt).scalars().all())


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
    rel.status = RelationshipStatus.rejected
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
