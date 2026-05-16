"""Relationship model: a connection between two users."""

from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, Enum, ForeignKey, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class RelationshipStatus(enum.StrEnum):
    pending = "pending"
    accepted = "accepted"


class Relationship(db.Model):
    __tablename__ = "relationship"
    __table_args__ = (
        CheckConstraint("user_a_id < user_b_id", name="ck_relationship_user_order"),
        UniqueConstraint("user_a_id", "user_b_id", name="uq_relationship_user_pair"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_a_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    user_b_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
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
