"""Password-reset service: email validation, request, and confirmation.

See ADR-0002. The request path is enumeration-resistant — it never
reveals whether an address is registered — and reset tokens are stored
only as hashes, single-use, and short-lived.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from flask import current_app
from sqlalchemy import select, update

from app.auth.security import generate_token, hash_password, hash_token
from app.extensions import db
from app.models import AuthToken, PasswordResetToken, User
from app.services import ValidationError
from app.services.email_sender import EmailMessage

# Matches the change-password minimum in app/auth/routes.py. Kept in
# sync deliberately; both gate the same credential.
MIN_PASSWORD_LENGTH = 8

_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def _as_utc(dt: datetime) -> datetime:
    # SQLite returns naive datetimes even from ``DateTime(timezone=True)``
    # columns. Treat naive values as UTC (the project invariant) so the
    # expiry comparison is timezone-aware on both sides.
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


def clean_email(value: object) -> str:
    """Validate and normalise an email address, or raise ``ValidationError``.

    Normalisation is strip + lowercase. Callers that accept a *nullable*
    email (register, profile update) should handle ``None``/empty
    themselves before calling this.
    """
    if not isinstance(value, str):
        raise ValidationError("invalid_email", "email must be a string.")
    normalised = value.strip().lower()
    if len(normalised) > 255 or not _EMAIL_RE.match(normalised):
        raise ValidationError("invalid_email", "email is not a valid address.")
    return normalised


def request_reset(email: object) -> None:
    """Best-effort: mint and email a reset token if a live account owns ``email``.

    Never raises for an unknown or invalid address and never reveals
    whether one is registered — the route returns the same response
    regardless. Commits its own transaction.
    """
    if not isinstance(email, str) or not email.strip():
        return
    normalised = email.strip().lower()

    user = db.session.execute(select(User).where(User.email == normalised)).scalar_one_or_none()
    if user is None or user.is_deleted:
        return

    now = datetime.now(UTC)
    # Invalidate any prior unused tokens so only the newest link works.
    db.session.execute(
        update(PasswordResetToken)
        .where(
            PasswordResetToken.user_id == user.id,
            PasswordResetToken.used_at.is_(None),
        )
        .values(used_at=now)
    )

    raw_token = generate_token()
    lifetime = current_app.config["PASSWORD_RESET_LIFETIME_MINUTES"]
    db.session.add(
        PasswordResetToken(
            user_id=user.id,
            token_hash=hash_token(raw_token),
            expires_at=now + timedelta(minutes=lifetime),
        )
    )
    db.session.commit()

    _send_reset_email(user.email, raw_token)


def _send_reset_email(to_addr: str, raw_token: str) -> None:
    base = current_app.config.get("PASSWORD_RESET_URL_BASE")
    if base:
        link = f"{base.rstrip('/')}/{raw_token}"
        instruction = f"Open this link to choose a new password:\n\n{link}\n"
    else:
        instruction = (
            f"Use this password-reset token in the app to choose a new password:\n\n{raw_token}\n"
        )
    lifetime = current_app.config["PASSWORD_RESET_LIFETIME_MINUTES"]
    body = (
        "Someone requested a password reset for your Parity account.\n\n"
        f"{instruction}\n"
        f"This token expires in {lifetime} minutes. If you did not request "
        "a reset, you can ignore this email — your password is unchanged.\n"
    )
    sender = current_app.extensions["email_sender"]
    sender.send(EmailMessage(to=to_addr, subject="Reset your Parity password", body=body))


def confirm_reset(token: object, new_password: object) -> None:
    """Consume a reset token and set a new password, revoking all sessions.

    Raises ``ValidationError`` on a bad token or weak password. Commits
    its own transaction on success.
    """
    if not isinstance(token, str) or not token.strip():
        raise ValidationError("invalid_token", "This reset link is invalid or has expired.")
    if not isinstance(new_password, str) or len(new_password) < MIN_PASSWORD_LENGTH:
        raise ValidationError(
            "weak_password",
            "new_password is too short.",
            details={"min_length": MIN_PASSWORD_LENGTH},
        )

    now = datetime.now(UTC)
    row = db.session.execute(
        select(PasswordResetToken).where(PasswordResetToken.token_hash == hash_token(token))
    ).scalar_one_or_none()
    if row is None or row.used_at is not None or _as_utc(row.expires_at) <= now:
        raise ValidationError("invalid_token", "This reset link is invalid or has expired.")

    user = db.session.get(User, row.user_id)
    if user is None or user.is_deleted:
        raise ValidationError("invalid_token", "This reset link is invalid or has expired.")

    user.password_hash = hash_password(new_password)
    row.used_at = now
    # A reset implies the account may be compromised: end every session.
    db.session.execute(
        update(AuthToken)
        .where(AuthToken.user_id == user.id, AuthToken.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    db.session.commit()
