"""Flask extension singletons.

Kept in their own module so models and blueprints can import them without
creating circular references through the application factory.
"""

from __future__ import annotations

from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_migrate import Migrate
from flask_sqlalchemy import SQLAlchemy
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Declarative base used by all ORM models."""


db = SQLAlchemy(model_class=Base)
migrate = Migrate()

# ``headers_enabled=True`` so 429 responses carry a ``Retry-After`` header
# (and successful responses on rate-limited endpoints expose the
# ``X-RateLimit-*`` informational headers). Per-endpoint key functions and
# limit strings are wired up in ``app/api/_rate_limits.py`` and the route
# modules; the singleton here only carries the constructor defaults.
limiter = Limiter(key_func=get_remote_address, headers_enabled=True)
