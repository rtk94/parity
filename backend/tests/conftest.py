"""Pytest fixtures: per-test in-memory DB with a fresh schema."""

from __future__ import annotations

from collections.abc import Generator

import pytest
from flask import Flask
from flask.testing import FlaskClient

from app import create_app
from app.extensions import db


@pytest.fixture
def app() -> Generator[Flask, None, None]:
    flask_app = create_app("testing")
    with flask_app.app_context():
        db.create_all()
        try:
            yield flask_app
        finally:
            db.session.remove()
            db.drop_all()
            # Release pooled sqlite connections explicitly. Without this,
            # Python 3.13 raises ResourceWarning for unclosed connections at
            # interpreter shutdown and pytest's filterwarnings=error treats
            # those as failures.
            db.engine.dispose()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()
