"""Parity backend application factory."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from flask import Flask
from sqlalchemy import event
from sqlalchemy.engine import Engine

from app.config import resolve_config
from app.errors import register_error_handlers
from app.extensions import db, limiter, migrate

# Load .env if present so config picks up SECRET_KEY / DATABASE_URL.
load_dotenv()


@event.listens_for(Engine, "connect")
def _sqlite_pragma_on_connect(dbapi_connection, connection_record):  # noqa: ARG001
    """Enforce foreign-key constraints on SQLite connections."""
    # The DBAPI connection class name is enough to detect SQLite without
    # importing sqlite3 conditionally; this is a no-op for other engines.
    if dbapi_connection.__class__.__module__.startswith("sqlite3"):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys = ON")
        cursor.close()


def create_app(
    config_name: str | None = None,
    overrides: dict[str, Any] | None = None,
) -> Flask:
    """Build a configured Flask app instance.

    ``overrides`` is merged into ``app.config`` after the named config is
    applied and before extensions initialise. It lets tests flip a
    setting (e.g. ``RATELIMIT_ENABLED``) without subclassing ``Config``.
    """
    instance_path = str(Path(__file__).resolve().parent.parent / "instance")
    app = Flask(__name__, instance_path=instance_path, instance_relative_config=True)
    os.makedirs(app.instance_path, exist_ok=True)

    app.config.from_object(resolve_config(config_name))
    if overrides:
        app.config.update(overrides)

    # Ensure SQLite URIs without an explicit absolute path land inside
    # backend/instance/ so they don't pollute the working directory.
    uri = app.config.get("SQLALCHEMY_DATABASE_URI", "")
    if uri.startswith("sqlite:///") and not uri.startswith("sqlite:////"):
        rel = uri[len("sqlite:///") :]
        if rel and rel != ":memory:" and not os.path.isabs(rel):
            app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///" + os.path.join(
                app.instance_path, rel
            )

    db.init_app(app)
    migrate.init_app(app, db)
    limiter.init_app(app)

    # Import models so SQLAlchemy + Alembic see them.
    from app import models  # noqa: F401
    from app.api import expenses_bp, health_bp, payments_bp, relationships_bp
    from app.auth import auth_bp

    app.register_blueprint(health_bp)
    app.register_blueprint(auth_bp)
    app.register_blueprint(relationships_bp)
    app.register_blueprint(expenses_bp)
    app.register_blueprint(payments_bp)

    register_error_handlers(app)

    return app
