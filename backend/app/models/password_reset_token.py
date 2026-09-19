"""PasswordResetToken model: single-use, hashed password-reset tokens.

Only the SHA-256 hash of the code is stored (as with ``auth_token``), and
the digest is scoped to the owning user so a short code cannot be guessed
across accounts. Codes are short-lived, single-use, and attempt-capped;
``used_at`` marks redemption or a spent attempt budget. See ADR-0002.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, String, func, text
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class PasswordResetToken(db.Model):
    __tablename__ = "password_reset_token"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False)
    # Indexed but *not* unique: the digest is user-scoped, so two accounts
    # drawing the same digits hash differently, and a repeat draw for one
    # account must not raise instead of simply superseding the old row.
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # Wrong guesses against this code. At PASSWORD_RESET_MAX_ATTEMPTS the
    # code is burned; this cap is what makes a short numeric code safe.
    attempts: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
        server_default=text("0"),
    )
