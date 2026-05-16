"""Flask extension singletons.

Kept in their own module so models and blueprints can import them without
creating circular references through the application factory.
"""

from __future__ import annotations

from flask_migrate import Migrate
from flask_sqlalchemy import SQLAlchemy
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Declarative base used by all ORM models."""


db = SQLAlchemy(model_class=Base)
migrate = Migrate()
