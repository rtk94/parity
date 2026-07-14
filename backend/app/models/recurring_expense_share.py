"""RecurringExpenseShare model: per-user allocation of a recurring-expense template."""

from __future__ import annotations

from sqlalchemy import CheckConstraint, ForeignKey, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class RecurringExpenseShare(db.Model):
    __tablename__ = "recurring_expense_share"
    __table_args__ = (
        CheckConstraint("amount_cents > 0", name="ck_recurring_expense_share_amount_positive"),
        UniqueConstraint(
            "recurring_expense_id",
            "user_id",
            name="uq_recurring_expense_share_recurring_user",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    recurring_expense_id: Mapped[int] = mapped_column(
        ForeignKey("recurring_expense.id"), nullable=False
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    amount_cents: Mapped[int] = mapped_column(nullable=False)
