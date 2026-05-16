"""ExpenseShare model: per-user allocation of an expense."""

from __future__ import annotations

from sqlalchemy import CheckConstraint, ForeignKey, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class ExpenseShare(db.Model):
    __tablename__ = "expense_share"
    __table_args__ = (
        CheckConstraint("amount_cents > 0", name="ck_expense_share_amount_positive"),
        UniqueConstraint("expense_id", "user_id", name="uq_expense_share_expense_user"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    expense_id: Mapped[int] = mapped_column(ForeignKey("expense.id"), nullable=False)
    user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    amount_cents: Mapped[int] = mapped_column(nullable=False)
