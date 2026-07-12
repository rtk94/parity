"""Regression test for .env loading order.

The ``Config`` classes read ``os.environ`` at class-definition time, so
``.env`` must be loaded before ``app.config`` is imported. A past bug
called ``load_dotenv()`` in ``app/__init__.py`` *after* importing
``app.config``, so ``.env`` values (``SECRET_KEY``,
``FCM_CREDENTIALS_FILE``, …) silently fell back to their defaults.

This runs a fresh interpreter (config is import-time state, so it can't
be re-evaluated in-process) whose only source for these two settings is a
``.env`` in its working directory.
"""

from __future__ import annotations

import os
import subprocess
import sys


def test_dotenv_values_reach_config(tmp_path):
    (tmp_path / ".env").write_text(
        "SECRET_KEY=from-dotenv-secret\nFCM_CREDENTIALS_FILE=/tmp/dotenv-marker.json\n"
    )
    script = (
        "import app;"
        "a = app.create_app('production');"
        "print('SK=' + a.config['SECRET_KEY']);"
        "print('FCM=' + str(a.config['FCM_CREDENTIALS_FILE']))"
    )
    # Strip these from the inherited environment so the .env is the only
    # possible source — otherwise a value in os.environ would mask the bug.
    env = {k: v for k, v in os.environ.items() if k not in ("SECRET_KEY", "FCM_CREDENTIALS_FILE")}

    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=tmp_path,
        capture_output=True,
        text=True,
        env=env,
    )

    assert result.returncode == 0, result.stderr
    assert "SK=from-dotenv-secret" in result.stdout
    assert "FCM=/tmp/dotenv-marker.json" in result.stdout
