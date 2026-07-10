#!/bin/sh
# Apply any pending migrations, then hand off to gunicorn.
# `exec` so gunicorn becomes PID 1 and receives signals directly.
set -e

flask db upgrade

exec gunicorn \
    --bind 0.0.0.0:8000 \
    --workers "${WEB_CONCURRENCY:-2}" \
    --timeout "${GUNICORN_TIMEOUT:-60}" \
    --access-logfile - \
    --error-logfile - \
    "app:create_app()"
