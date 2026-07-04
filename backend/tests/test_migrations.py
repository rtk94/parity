"""Guard: the migrated schema must carry the same triggers as create_all.

The test suite normally builds schemas with ``db.create_all()``, which
installs triggers via the ``after_create`` listener — so a migration
that silently drops triggers (SQLite's table-recreate does exactly
that) is invisible to every other test. This module runs the real
Alembic chain against a file-backed database and diffs the trigger set
against the canonical list in ``app.models._triggers``.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from flask_migrate import upgrade as alembic_upgrade
from sqlalchemy import text

from app import create_app
from app.extensions import db
from app.models._triggers import TRIGGER_NAMES_REVERSED


# Flask-Migrate's stock env.py still calls the deprecated
# ``get_engine``; that's third-party noise, not a project regression,
# so exempt it from the suite-wide warnings-as-errors policy here.
@pytest.mark.filterwarnings("ignore:'get_engine' is deprecated:DeprecationWarning")
def test_migrated_schema_has_all_immutability_triggers(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path}/migrated.db")
    app = create_app("development")
    with app.app_context():
        alembic_upgrade()
        rows = db.session.execute(
            text("SELECT name FROM sqlite_schema WHERE type = 'trigger'")
        ).all()
        migrated_triggers = {row[0] for row in rows}
        db.session.remove()
        db.engine.dispose()

    assert migrated_triggers == set(TRIGGER_NAMES_REVERSED)
