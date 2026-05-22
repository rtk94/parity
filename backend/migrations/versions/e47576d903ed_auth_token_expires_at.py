"""auth_token: add expires_at column

Revision ID: e47576d903ed
Revises: 08408c17c7f4
Create Date: 2026-05-22 21:00:00.000000

Phase 4 token lifecycle: ``auth_token`` gains an ``expires_at``
timestamp set at insert time. The application enforces both an idle
window (sliding from ``last_used_at``) and an absolute window (the
``expires_at`` column itself, computed as ``created_at +
TOKEN_ABSOLUTE_LIFETIME_DAYS``); see ``app.auth.decorators``.

SQLite cannot ``ALTER TABLE ... ALTER COLUMN`` to change a column's
nullability, so this migration uses three steps inside
``op.batch_alter_table`` plus a raw backfill:

1. Add ``expires_at`` as nullable so existing rows do not violate the
   new constraint while the column is still empty.
2. Backfill every row with ``created_at + 365 days``. The 365-day
   offset is hard-coded rather than read from
   ``TOKEN_ABSOLUTE_LIFETIME_DAYS`` because Alembic migrations should
   be deterministic and not depend on runtime configuration; the
   application uses the configured value going forward.
3. Alter the column to NOT NULL.
"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'e47576d903ed'
down_revision = '08408c17c7f4'
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("auth_token", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True)
        )

    # Backfill: every existing token gets a 365-day absolute lifetime
    # measured from its ``created_at``. SQLite's ``datetime`` accepts an
    # ISO timestamp plus a modifier like ``+365 days``.
    op.execute(
        "UPDATE auth_token "
        "SET expires_at = datetime(created_at, '+365 days') "
        "WHERE expires_at IS NULL"
    )

    with op.batch_alter_table("auth_token", schema=None) as batch_op:
        batch_op.alter_column(
            "expires_at",
            existing_type=sa.DateTime(timezone=True),
            nullable=False,
        )


def downgrade():
    with op.batch_alter_table("auth_token", schema=None) as batch_op:
        batch_op.drop_column("expires_at")
