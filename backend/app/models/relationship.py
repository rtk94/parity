"""Relationship model: a connection between two users."""

from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Enum,
    ForeignKey,
    Index,
    func,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.extensions import db
from app.models.user import User


class RelationshipStatus(enum.StrEnum):
    pending = "pending"
    accepted = "accepted"
    rejected = "rejected"


class Relationship(db.Model):
    __tablename__ = "relationship"
    __table_args__ = (
        CheckConstraint(
            "inviting_user_id != invited_user_id",
            name="ck_relationship_distinct_parties",
        ),
        Index(
            "uq_relationship_user_pair",
            text("MIN(inviting_user_id, invited_user_id)"),
            text("MAX(inviting_user_id, invited_user_id)"),
            unique=True,
            sqlite_where=text("status != 'rejected'"),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    inviting_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    invited_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    status: Mapped[RelationshipStatus] = mapped_column(
        Enum(RelationshipStatus, name="relationship_status"),
        nullable=False,
        default=RelationshipStatus.pending,
        server_default=RelationshipStatus.pending.value,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

    inviting_user: Mapped[User] = relationship(User, foreign_keys=[inviting_user_id])
    invited_user: Mapped[User] = relationship(User, foreign_keys=[invited_user_id])

    def parties(self) -> set[int]:
        return {self.inviting_user_id, self.invited_user_id}
