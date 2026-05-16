"""Payment model: a transfer of money from one user to another."""

from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, Enum, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class PaymentStatus(enum.StrEnum):
    pending = "pending"
    confirmed = "confirmed"
    discarded = "discarded"


class Payment(db.Model):
    __tablename__ = "payment"
    __table_args__ = (
        CheckConstraint("amount_cents > 0", name="ck_payment_amount_positive"),
        CheckConstraint("from_user_id != to_user_id", name="ck_payment_distinct_parties"),
        CheckConstraint(
            "confirmed_by_user_id IS NULL OR confirmed_by_user_id != created_by_user_id",
            name="ck_payment_no_self_confirm",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    from_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    to_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    relationship_id: Mapped[int] = mapped_column(ForeignKey("relationship.id"), nullable=False)
    amount_cents: Mapped[int] = mapped_column(nullable=False)
    description: Mapped[str | None] = mapped_column(String(512), nullable=True)
    created_by_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    status: Mapped[PaymentStatus] = mapped_column(
        Enum(PaymentStatus, name="payment_status"),
        nullable=False,
        default=PaymentStatus.pending,
        server_default=PaymentStatus.pending.value,
    )
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    confirmed_by_user_id: Mapped[int | None] = mapped_column(ForeignKey("user.id"), nullable=True)
    discarded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    discarded_by_user_id: Mapped[int | None] = mapped_column(ForeignKey("user.id"), nullable=True)
    rejection_reason: Mapped[str | None] = mapped_column(String(512), nullable=True)
    reverses_payment_id: Mapped[int | None] = mapped_column(ForeignKey("payment.id"), nullable=True)
