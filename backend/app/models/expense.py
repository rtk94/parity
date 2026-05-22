"""Expense model: an expense paid by one user, owed by participants in shares."""

from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, Enum, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.extensions import db
from app.models.expense_share import ExpenseShare


class ExpenseStatus(enum.StrEnum):
    pending = "pending"
    confirmed = "confirmed"
    discarded = "discarded"


class Expense(db.Model):
    __tablename__ = "expense"
    __table_args__ = (
        CheckConstraint("total_cents > 0", name="ck_expense_total_positive"),
        CheckConstraint(
            "confirmed_by_user_id IS NULL OR confirmed_by_user_id != created_by_user_id",
            name="ck_expense_no_self_confirm",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    payer_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    relationship_id: Mapped[int] = mapped_column(ForeignKey("relationship.id"), nullable=False)
    total_cents: Mapped[int] = mapped_column(nullable=False)
    description: Mapped[str] = mapped_column(String(512), nullable=False)
    created_by_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    status: Mapped[ExpenseStatus] = mapped_column(
        Enum(ExpenseStatus, name="expense_status"),
        nullable=False,
        default=ExpenseStatus.pending,
        server_default=ExpenseStatus.pending.value,
    )
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    confirmed_by_user_id: Mapped[int | None] = mapped_column(ForeignKey("user.id"), nullable=True)
    discarded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    discarded_by_user_id: Mapped[int | None] = mapped_column(ForeignKey("user.id"), nullable=True)
    rejection_reason: Mapped[str | None] = mapped_column(String(512), nullable=True)
    reverses_expense_id: Mapped[int | None] = mapped_column(ForeignKey("expense.id"), nullable=True)

    shares: Mapped[list[ExpenseShare]] = relationship(
        ExpenseShare,
        primaryjoin="ExpenseShare.expense_id == Expense.id",
        order_by="ExpenseShare.id",
        foreign_keys="ExpenseShare.expense_id",
    )
