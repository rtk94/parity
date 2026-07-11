"""DeviceToken model: per-device push registration tokens.

Each row is a single device's push token (e.g. an FCM registration
token) owned by the user currently signed in on that device. Tokens are
globally unique; a device that switches accounts re-registers the same
token under the new user. Rows are plain mutable bookkeeping — they are
not part of the immutable ledger and carry no DB-level triggers.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.extensions import db


class DeviceToken(db.Model):
    __tablename__ = "device_token"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("user.id"), nullable=False, index=True)
    # The opaque push token from the platform (FCM registration token).
    # Unique so a device maps to exactly one row regardless of which
    # account it last registered under. Treated as a secret: never logged.
    token: Mapped[str] = mapped_column(String(512), unique=True, nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False, default="android")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

    def to_dict(self) -> dict[str, Any]:
        # Local import avoids a models -> api import cycle (the
        # serializers module imports models).
        from app.api._serializers import iso8601_z

        return {
            "id": self.id,
            "platform": self.platform,
            "created_at": iso8601_z(self.created_at),
            "last_seen_at": iso8601_z(self.last_seen_at),
        }
