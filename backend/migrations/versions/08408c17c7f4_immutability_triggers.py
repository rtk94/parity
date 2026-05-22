"""immutability triggers

Revision ID: 08408c17c7f4
Revises: 2d79c4789eb6
Create Date: 2026-05-22 19:00:00.000000

Phase 3 hardening: install DB-level immutability triggers on the ledger
tables (``expense``, ``expense_share``, ``payment``, ``relationship``).

The triggers are a backstop — application code already enforces these
invariants through the service layer — so they should never fire in
normal operation. Their value is catching service-layer bugs and
preventing damage from direct SQL access.

The trigger DDL is imported from :mod:`app.models._triggers` so the
model layer (which installs the same statements via a SQLAlchemy
``after_create`` listener for tests that go through ``db.create_all``)
and this migration stay in sync. If you change a trigger, write a new
migration; this one is frozen.
"""

from alembic import op

from app.models._triggers import DROP_STATEMENTS, TRIGGER_STATEMENTS

# revision identifiers, used by Alembic.
revision = '08408c17c7f4'
down_revision = '2d79c4789eb6'
branch_labels = None
depends_on = None


def upgrade():
    for stmt in TRIGGER_STATEMENTS:
        op.execute(stmt)


def downgrade():
    for stmt in DROP_STATEMENTS:
        op.execute(stmt)
