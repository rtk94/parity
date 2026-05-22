"""Application configuration classes."""

from __future__ import annotations

import os
from pathlib import Path


class Config:
    SECRET_KEY: str = os.environ.get("SECRET_KEY", "dev-only-not-secret")
    SQLALCHEMY_DATABASE_URI: str = os.environ.get("DATABASE_URL", "sqlite:///parity.db")
    SQLALCHEMY_TRACK_MODIFICATIONS: bool = False
    JSON_SORT_KEYS: bool = False

    # Rate limiting (Flask-Limiter). The defaults are sized for a single
    # self-hosted instance; tighten or relax via the corresponding env
    # variables or by subclassing ``Config``.
    RATELIMIT_ENABLED: bool = True
    RATELIMIT_STORAGE_URI: str = os.environ.get("RATELIMIT_STORAGE_URI", "memory://")
    RATELIMIT_LOGIN_IP: str = os.environ.get("RATELIMIT_LOGIN_IP", "5 per minute")
    RATELIMIT_LOGIN_USERNAME: str = os.environ.get("RATELIMIT_LOGIN_USERNAME", "20 per hour")
    RATELIMIT_REGISTER: str = os.environ.get("RATELIMIT_REGISTER", "5 per hour")
    RATELIMIT_WRITE: str = os.environ.get("RATELIMIT_WRITE", "60 per minute")
    RATELIMIT_CHANGE_PASSWORD: str = os.environ.get("RATELIMIT_CHANGE_PASSWORD", "5 per hour")
    RATELIMIT_REFRESH: str = os.environ.get("RATELIMIT_REFRESH", "10 per hour")

    # Bearer token lifetimes. Idle is sliding from ``last_used_at``;
    # absolute is a hard cap from ``created_at``.
    TOKEN_ABSOLUTE_LIFETIME_DAYS: int = int(os.environ.get("TOKEN_ABSOLUTE_LIFETIME_DAYS", "365"))
    TOKEN_IDLE_LIFETIME_DAYS: int = int(os.environ.get("TOKEN_IDLE_LIFETIME_DAYS", "30"))


class DevelopmentConfig(Config):
    DEBUG: bool = True


class TestingConfig(Config):
    TESTING: bool = True
    DEBUG: bool = False
    SECRET_KEY: str = "test-secret-key"
    SQLALCHEMY_DATABASE_URI: str = "sqlite:///:memory:"
    WTF_CSRF_ENABLED: bool = False
    # Phase 1/2 tests run against an unrate-limited app so they can issue
    # large numbers of requests per fixture without tripping limits. The
    # dedicated ``rate_limited_app`` fixture in ``tests/test_rate_limit.py``
    # overrides this to ``True``.
    RATELIMIT_ENABLED: bool = False


class ProductionConfig(Config):
    DEBUG: bool = False


CONFIG_MAP: dict[str, type[Config]] = {
    "development": DevelopmentConfig,
    "testing": TestingConfig,
    "production": ProductionConfig,
}


def resolve_config(name: str | None) -> type[Config]:
    """Resolve a config class by name, defaulting to development."""
    key = (name or os.environ.get("FLASK_ENV") or "development").lower()
    return CONFIG_MAP.get(key, DevelopmentConfig)


# Re-exported for callers that want to know where the package lives.
PACKAGE_DIR: Path = Path(__file__).resolve().parent
