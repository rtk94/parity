# Parity backend — production image.
# Runs the Flask app under gunicorn; runs migrations on startup.
FROM python:3.13-slim

# No .pyc, unbuffered stdout/stderr for container logs.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    FLASK_APP=app:create_app \
    FLASK_ENV=production \
    DATABASE_URL=sqlite:////data/parity.db

WORKDIR /app

# Install dependencies first for layer caching, then the app itself.
# argon2-cffi ships manylinux wheels, so no compiler toolchain is needed.
COPY pyproject.toml ./
COPY app ./app
COPY migrations ./migrations
RUN pip install --no-cache-dir ".[prod]"

COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# Non-root runtime. The SQLite volume lives at /data, owned by appuser
# so the named volume inherits writable ownership on first creation.
RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /data \
    && chown -R appuser:appuser /app /data
USER appuser

EXPOSE 8000
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/api/v1/health')"

ENTRYPOINT ["docker-entrypoint.sh"]
