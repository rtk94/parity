"""Attachment model: a supporting file (receipt, etc.) attached to an expense.

Only metadata lives here; the bytes live in object storage keyed by
``storage_key`` (see ADR-0003). Attachments are supplementary evidence,
not immutable ledger rows — they may be added to confirmed expenses and
deleted by their uploader.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class Attachment(db.Model):
    __tablename__ = "attachment"
    __table_args__ = (CheckConstraint("size_bytes > 0", name="ck_attachment_size_positive"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    expense_id: Mapped[int] = mapped_column(ForeignKey("expense.id"), nullable=False)
    uploaded_by_user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    content_type: Mapped[str] = mapped_column(String(128), nullable=False)
    size_bytes: Mapped[int] = mapped_column(nullable=False)
    checksum_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    # Opaque object-storage key; never exposed to clients.
    storage_key: Mapped[str] = mapped_column(String(255), unique=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
