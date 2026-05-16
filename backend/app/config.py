"""Application configuration classes."""

from __future__ import annotations

import os
from pathlib import Path


class Config:
    SECRET_KEY: str = os.environ.get("SECRET_KEY", "dev-only-not-secret")
    SQLALCHEMY_DATABASE_URI: str = os.environ.get("DATABASE_URL", "sqlite:///parity.db")
    SQLALCHEMY_TRACK_MODIFICATIONS: bool = False
    JSON_SORT_KEYS: bool = False


class DevelopmentConfig(Config):
    DEBUG: bool = True


class TestingConfig(Config):
    TESTING: bool = True
    DEBUG: bool = False
    SECRET_KEY: str = "test-secret-key"
    SQLALCHEMY_DATABASE_URI: str = "sqlite:///:memory:"
    WTF_CSRF_ENABLED: bool = False


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
