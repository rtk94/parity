"""reinstall relationship triggers lost in the phase 5 table recreate

Revision ID: c4d8f0a91e22
Revises: 7e5a2c9d41bb
Create Date: 2026-07-03 17:30:00.000000

The Phase 5 migration (d3f798f5447a) used ``batch_alter_table`` on
``relationship``, which recreates the table and silently drops every
trigger attached to it. That migration re-created
``trg_relationship_immutable_columns`` (it needed to widen it anyway)
but not ``trg_relationship_no_delete`` or
``trg_relationship_no_update_terminal`` — so databases migrated
through Phase 5 are missing both, while fresh ``db.create_all()``
schemas (used by the test suite) have them. This migration converges
the two worlds by reinstalling the missing triggers idempotently.
"""

from alembic import op

# revision identifiers, used by Alembic.
revision = "c4d8f0a91e22"
down_revision = "7e5a2c9d41bb"
branch_labels = None
depends_on = None


_TRIGGERS = [
    """
    CREATE TRIGGER trg_relationship_no_delete
    BEFORE DELETE ON relationship
    FOR EACH ROW
    BEGIN
        SELECT RAISE(ABORT, 'relationship_immutable: delete forbidden');
    END
    """,
    """
    CREATE TRIGGER trg_relationship_no_update_terminal
    BEFORE UPDATE ON relationship
    FOR EACH ROW
    WHEN OLD.status != 'pending'
    BEGIN
        SELECT RAISE(ABORT, 'relationship_immutable: update on terminal row');
    END
    """,
]


def upgrade():
    op.execute("DROP TRIGGER IF EXISTS trg_relationship_no_delete")
    op.execute("DROP TRIGGER IF EXISTS trg_relationship_no_update_terminal")
    for stmt in _TRIGGERS:
        op.execute(stmt)


def downgrade():
    # No-op: these triggers were always supposed to exist (the Phase 3
    # migration installed them); removing them again would just
    # re-introduce the drift this migration repairs.
    pass
