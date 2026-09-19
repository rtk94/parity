"""Password-reset service: email validation, request, and confirmation.

See ADR-0002 and its amendment. The request path is enumeration-resistant
— it never reveals whether an address is registered — and reset codes are
stored only as hashes, single-use, short-lived, and attempt-capped.
"""

from __future__ import annotations

import re
import secrets
from datetime import UTC, datetime, timedelta

from flask import current_app
from sqlalchemy import select, update

from app.auth.security import hash_password, hash_token
from app.extensions import db
from app.models import AuthToken, PasswordResetToken, User
from app.services import ValidationError
from app.services.email_sender import EmailMessage

# Matches the change-password minimum in app/auth/routes.py. Kept in
# sync deliberately; both gate the same credential.
MIN_PASSWORD_LENGTH = 8

# Length of the emailed reset code. Short enough to retype from a phone,
# long enough that guessing it is hopeless once the per-code attempt cap
# and the short expiry are in play.
RESET_CODE_LENGTH = 8

_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

# Characters people add when copying a code out of an email client.
_CODE_NOISE_RE = re.compile(r"[\s\-‐-―]")


def _as_utc(dt: datetime) -> datetime:
    # SQLite returns naive datetimes even from ``DateTime(timezone=True)``
    # columns. Treat naive values as UTC (the project invariant) so the
    # expiry comparison is timezone-aware on both sides.
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


def clean_email(value: object) -> str:
    """Validate and normalise an email address, or raise ``ValidationError``.

    Normalisation is strip + lowercase. An address is now *required* on
    every live account (ADR-0002 amendment), so callers reject a missing
    or blank value rather than treating it as "no email".
    """
    if not isinstance(value, str):
        raise ValidationError("invalid_email", "email must be a string.")
    normalised = value.strip().lower()
    if len(normalised) > 255 or not _EMAIL_RE.match(normalised):
        raise ValidationError("invalid_email", "email is not a valid address.")
    return normalised


def generate_reset_code() -> str:
    """Return a fresh zero-padded ``RESET_CODE_LENGTH``-digit reset code."""
    return f"{secrets.randbelow(10**RESET_CODE_LENGTH):0{RESET_CODE_LENGTH}d}"


def _normalise_code(value: str) -> str:
    """Strip the spaces and dashes people paste along with a code."""
    return _CODE_NOISE_RE.sub("", value.strip())


def _code_hash(user_id: int, code: str) -> str:
    """Hash a reset code *scoped to its owner*.

    Binding the user id into the digest is load-bearing, not decoration.
    An unscoped 8-digit code would be guessable against *any* account
    holding a live code, turning a per-account guess into a pooled one;
    it would also let two users who happened to draw the same digits
    collide in the token table.
    """
    return hash_token(f"{user_id}:{code}")


def request_reset(email: object) -> None:
    """Best-effort: mint and email a reset code if a live account owns ``email``.

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
    # Invalidate any prior unused codes so only the newest one works.
    db.session.execute(
        update(PasswordResetToken)
        .where(
            PasswordResetToken.user_id == user.id,
            PasswordResetToken.used_at.is_(None),
        )
        .values(used_at=now)
    )

    code = generate_reset_code()
    lifetime = current_app.config["PASSWORD_RESET_LIFETIME_MINUTES"]
    db.session.add(
        PasswordResetToken(
            user_id=user.id,
            token_hash=_code_hash(user.id, code),
            expires_at=now + timedelta(minutes=lifetime),
        )
    )
    db.session.commit()

    _send_reset_email(user.email, code)


def _send_reset_email(to_addr: str, code: str) -> None:
    lifetime = current_app.config["PASSWORD_RESET_LIFETIME_MINUTES"]
    body = (
        "Someone requested a password reset for your Parity account.\n\n"
        f"Your reset code is:\n\n    {code}\n\n"
        f"Enter it in the Parity app within {lifetime} minutes to choose a "
        "new password. The code can only be used once.\n\n"
        "If you did not request a reset, you can ignore this email — your "
        "password is unchanged.\n"
    )
    sender = current_app.extensions["email_sender"]
    sender.send(EmailMessage(to=to_addr, subject="Your Parity password reset code", body=body))


def send_welcome_email(user: User) -> None:
    """Best-effort: confirm the recovery address on a new account.

    This doubles as a live delivery test at the moment the address is
    entered, so a typo surfaces at signup rather than at the point the
    user has locked themselves out and needs it to work.
    """
    if not user.email:
        return
    body = (
        f"Welcome to Parity, {user.display_name}.\n\n"
        f"This address ({user.email}) is now the recovery address for the "
        f"account '{user.username}'. It is the only way to reset your "
        "password if you forget it, so if this does not look right, change "
        "it in the app under Settings.\n\n"
        "Parity will never email you asking for your password.\n"
    )
    sender = current_app.extensions["email_sender"]
    sender.send(EmailMessage(to=user.email, subject="Your Parity recovery address", body=body))


# Every failure mode of ``confirm_reset`` reports this same error. A
# caller must not be able to tell a wrong address from a wrong code, an
# expired code, or one whose attempts are spent.
_INVALID = ("invalid_token", "This reset code is invalid or has expired.")


def confirm_reset(email: object, code: object, new_password: object) -> None:
    """Consume a reset code and set a new password, revoking all sessions.

    Raises ``ValidationError`` on a bad address/code or a weak password.
    Commits its own transaction on success.
    """
    if not isinstance(email, str) or not email.strip():
        raise ValidationError(*_INVALID)
    if not isinstance(code, str) or not _normalise_code(code):
        raise ValidationError(*_INVALID)
    if not isinstance(new_password, str) or len(new_password) < MIN_PASSWORD_LENGTH:
        raise ValidationError(
            "weak_password",
            "new_password is too short.",
            details={"min_length": MIN_PASSWORD_LENGTH},
        )

    normalised_email = email.strip().lower()
    user = db.session.execute(
        select(User).where(User.email == normalised_email)
    ).scalar_one_or_none()
    if user is None or user.is_deleted:
        raise ValidationError(*_INVALID)

    now = datetime.now(UTC)
    # Newest first: ``request_reset`` invalidates prior unused codes, so
    # there is normally at most one live row — but two interleaved
    # requests could leave two, and that must not be a 500.
    row = (
        db.session.execute(
            select(PasswordResetToken)
            .where(
                PasswordResetToken.user_id == user.id,
                PasswordResetToken.used_at.is_(None),
            )
            .order_by(PasswordResetToken.id.desc())
            .limit(1)
        )
        .scalars()
        .first()
    )
    if row is None or _as_utc(row.expires_at) <= now:
        raise ValidationError(*_INVALID)

    if row.token_hash != _code_hash(user.id, _normalise_code(code)):
        # A wrong guess against a live code. Count it, and burn the code
        # once the budget is spent — this attempt cap, not the per-IP
        # rate limit, is what makes a short code safe.
        row.attempts += 1
        if row.attempts >= current_app.config["PASSWORD_RESET_MAX_ATTEMPTS"]:
            row.used_at = now
        db.session.commit()
        raise ValidationError(*_INVALID)

    user.password_hash = hash_password(new_password)
    row.used_at = now
    # A reset implies the account may be compromised: end every session.
    db.session.execute(
        update(AuthToken)
        .where(AuthToken.user_id == user.id, AuthToken.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    db.session.commit()
