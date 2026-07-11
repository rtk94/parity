"""Device service: register and unregister push tokens.

A device registers its push token after login and unregisters it on
logout. Tokens are globally unique, so registering a token that already
exists re-assigns it to the caller (the same physical device signing in
under a different account) rather than creating a duplicate.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import delete, select

from app.extensions import db
from app.models import DeviceToken, User
from app.services import ValidationError

# FCM registration tokens are ~150+ chars and can grow; 512 is a safe
# ceiling that matches the column width.
_MAX_TOKEN_LEN = 512
_ALLOWED_PLATFORMS = frozenset({"android"})


def register_device(user: User, token: str | None, platform: str = "android") -> DeviceToken:
    """Register (or re-assign) ``token`` to ``user`` and return the row."""
    token = (token or "").strip()
    if not token:
        raise ValidationError("invalid_token", "A device token is required.")
    if len(token) > _MAX_TOKEN_LEN:
        raise ValidationError("invalid_token", "Device token is too long.")
    if platform not in _ALLOWED_PLATFORMS:
        raise ValidationError(
            "invalid_platform",
            "Unsupported device platform.",
            {"allowed": sorted(_ALLOWED_PLATFORMS)},
        )

    now = datetime.now(UTC)
    existing = db.session.execute(
        select(DeviceToken).where(DeviceToken.token == token)
    ).scalar_one_or_none()
    if existing is not None:
        # The device may have belonged to another account (re-install or
        # a different user signing in); re-assign it to the caller.
        existing.user_id = user.id
        existing.platform = platform
        existing.last_seen_at = now
        row = existing
    else:
        row = DeviceToken(user_id=user.id, token=token, platform=platform, last_seen_at=now)
        db.session.add(row)
    db.session.commit()
    return row


def unregister_device(user: User, token: str | None) -> None:
    """Remove ``token`` if it belongs to ``user``. Idempotent."""
    token = (token or "").strip()
    if not token:
        raise ValidationError("invalid_token", "A device token is required.")
    db.session.execute(
        delete(DeviceToken).where(
            DeviceToken.token == token,
            DeviceToken.user_id == user.id,
        )
    )
    db.session.commit()
