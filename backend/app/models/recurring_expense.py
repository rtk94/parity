"""RecurringExpense model: a template that generates pending expenses on a schedule.

A recurring expense is *configuration*, not a ledger entry — it is
mutable and deletable, unlike the immutable expenses it produces. Each
time the schedule comes due, the template materialises a fresh pending
expense (plus its shares) that the counterparty confirms through the
normal two-party flow.
"""

from __future__ import annotations

import enum
from datetime import date, datetime

from sqlalchemy import CheckConstraint, Date, DateTime, Enum, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.extensions import db
from app.models.recurring_expense_share import RecurringExpenseShare


class RecurringInterval(enum.StrEnum):
    daily = "daily"
    weekly = "weekly"
    monthly = "monthly"


class RecurringExpense(db.Model):
    __tablename__ = "recurring_expense"
    __table_args__ = (
        CheckConstraint("total_cents > 0", name="ck_recurring_expense_total_positive"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    relationship_id: Mapped[int] = mapped_column(ForeignKey("relationship.id"), nullable=False)
    payer_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    total_cents: Mapped[int] = mapped_column(nullable=False)
    description: Mapped[str] = mapped_column(String(512), nullable=False)
    category: Mapped[str | None] = mapped_column(String(64), nullable=True)
    interval: Mapped[RecurringInterval] = mapped_column(
        Enum(RecurringInterval, name="recurring_interval"),
        nullable=False,
    )
    # The next calendar date (UTC) on which this template is due to fire.
    next_run_on: Mapped[date] = mapped_column(Date, nullable=False)
    active: Mapped[bool] = mapped_column(
        nullable=False,
        default=True,
        server_default="1",
    )
    created_by_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    last_run_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    shares: Mapped[list[RecurringExpenseShare]] = relationship(
        RecurringExpenseShare,
        primaryjoin="RecurringExpenseShare.recurring_expense_id == RecurringExpense.id",
        order_by="RecurringExpenseShare.id",
        foreign_keys="RecurringExpenseShare.recurring_expense_id",
        cascade="all, delete-orphan",
    )
