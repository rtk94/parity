"""PasswordResetToken model: single-use, hashed password-reset tokens.

Only the SHA-256 hash of the raw token is stored (as with ``auth_token``),
so a database leak never exposes a usable reset link. Tokens are short-
lived and single-use; ``used_at`` marks redemption. See ADR-0002.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class PasswordResetToken(db.Model):
    __tablename__ = "password_reset_token"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
