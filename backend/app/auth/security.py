"""Password hashing and bearer-token helpers."""

from __future__ import annotations

import hashlib
import secrets
from datetime import UTC, datetime, timedelta

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from flask import current_app

_hasher = PasswordHasher()

# Computed once at import time. The value is irrelevant; it is never
# verified against a real password. Its only purpose is to give the
# "user not found" branch of login the same wall-clock cost as the
# "user found, wrong password" branch, defeating username enumeration
# by response-time analysis.
_DUMMY_HASH = _hasher.hash("parity_timing_equalizer_do_not_match")


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    try:
        return _hasher.verify(password_hash, password)
    except VerifyMismatchError:
        return False
    except Exception:
        return False


def verify_dummy_password(password: str) -> None:
    """Run argon2 verify against a fixed dummy hash, discarding the result.

    Called from the login flow when the supplied username does not exist,
    so that branch has the same wall-clock cost as the "user found,
    wrong password" branch. Any verify outcome is acceptable — the call
    is performed purely for its CPU cost.
    """
    try:
        _hasher.verify(_DUMMY_HASH, password)
    except VerifyMismatchError:
        pass
    except Exception:
        pass


def generate_token() -> str:
    """Generate a fresh opaque bearer token (raw, unhashed)."""
    return secrets.token_urlsafe(32)


def hash_token(raw_token: str) -> str:
    """SHA-256 hex digest of a raw bearer token."""
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()


def token_absolute_expiry(created_at: datetime) -> datetime:
    """Compute ``expires_at`` for a token created at ``created_at``."""
    days = current_app.config["TOKEN_ABSOLUTE_LIFETIME_DAYS"]
    base = created_at if created_at.tzinfo is not None else created_at.replace(tzinfo=UTC)
    return base + timedelta(days=days)
