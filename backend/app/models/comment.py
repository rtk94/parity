"""Comment model: free-text comments on an expense or payment."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class Comment(db.Model):
    __tablename__ = "comment"
    __table_args__ = (
        CheckConstraint(
            "(expense_id IS NOT NULL AND payment_id IS NULL) OR "
            "(expense_id IS NULL AND payment_id IS NOT NULL)",
            name="ck_comment_one_parent",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    expense_id: Mapped[int | None] = mapped_column(ForeignKey("expense.id"), nullable=True)
    payment_id: Mapped[int | None] = mapped_column(ForeignKey("payment.id"), nullable=True)
    content: Mapped[str] = mapped_column(String(512), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
