"""relationship: add currency_code column

Revision ID: d3f798f5447a
Revises: e47576d903ed
Create Date: 2026-05-24 12:30:00.000000

Phase 5: every ``relationship`` row carries a three-letter uppercase
ASCII currency code, supplied at creation and immutable thereafter. The
column lands with a NOT NULL constraint and a DB-level CHECK
(``currency_code GLOB '[A-Z][A-Z][A-Z]'``) as a backstop for the
application-layer format validation. Pre-existing rows in dev/test
databases are backfilled with ``'USD'`` so the NOT NULL alter succeeds.

The migration also rewrites the Phase 3
``trg_relationship_immutable_columns`` trigger to add ``currency_code``
to its protected-column list. The other relationship triggers
(``trg_relationship_no_delete``, ``trg_relationship_no_update_terminal``)
are unchanged. The trigger ``CREATE`` statements for both the new and
old versions are reproduced inline here rather than imported from
``app.models._triggers``: each migration is a snapshot of intent at
its revision, and the trigger DDL in that module is allowed to drift
forward as later phases land.

The second ``batch_alter_table`` call recreates the table (SQLite has
to copy in order to alter nullability and attach a new CHECK), which
drops attached objects that aren't embedded in the table DDL.  The
Phase 2 partial expression unique index ``uq_relationship_user_pair``
is one such object, so it is dropped explicitly before the recreate
and re-installed afterwards via the same raw-SQL form that Phase 2
used.
"""

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = "d3f798f5447a"
down_revision = "e47576d903ed"
branch_labels = None
depends_on = None


_NEW_IMMUTABLE_COLUMNS_TRIGGER = """
CREATE TRIGGER trg_relationship_immutable_columns
BEFORE UPDATE ON relationship
FOR EACH ROW
WHEN
    NEW.inviting_user_id IS NOT OLD.inviting_user_id
    OR NEW.invited_user_id IS NOT OLD.invited_user_id
    OR NEW.created_at IS NOT OLD.created_at
    OR NEW.currency_code IS NOT OLD.currency_code
BEGIN
    SELECT RAISE(ABORT, 'relationship_immutable: protected column changed');
END
"""


_OLD_IMMUTABLE_COLUMNS_TRIGGER = """
CREATE TRIGGER trg_relationship_immutable_columns
BEFORE UPDATE ON relationship
FOR EACH ROW
WHEN
    NEW.inviting_user_id IS NOT OLD.inviting_user_id
    OR NEW.invited_user_id IS NOT OLD.invited_user_id
    OR NEW.created_at IS NOT OLD.created_at
BEGIN
    SELECT RAISE(ABORT, 'relationship_immutable: protected column changed');
END
"""


_UQ_RELATIONSHIP_USER_PAIR = (
    "CREATE UNIQUE INDEX uq_relationship_user_pair "
    "ON relationship ("
    "MIN(inviting_user_id, invited_user_id), "
    "MAX(inviting_user_id, invited_user_id)"
    ") WHERE status != 'rejected'"
)


def upgrade():
    with op.batch_alter_table("relationship", schema=None) as batch_op:
        batch_op.add_column(sa.Column("currency_code", sa.String(length=3), nullable=True))

    op.execute("UPDATE relationship SET currency_code = 'USD' WHERE currency_code IS NULL")

    # The next batch_alter_table recreates the table (SQLite has to
    # copy to change nullability and attach a CHECK). The partial
    # expression unique index from Phase 2 is not embedded in the
    # table DDL, so drop it explicitly first and re-create it after.
    op.execute("DROP INDEX IF EXISTS uq_relationship_user_pair")

    with op.batch_alter_table("relationship", schema=None) as batch_op:
        batch_op.alter_column(
            "currency_code",
            existing_type=sa.String(length=3),
            nullable=False,
        )
        batch_op.create_check_constraint(
            "ck_relationship_currency_format",
            "currency_code GLOB '[A-Z][A-Z][A-Z]'",
        )

    op.execute(_UQ_RELATIONSHIP_USER_PAIR)

    op.execute("DROP TRIGGER IF EXISTS trg_relationship_immutable_columns")
    op.execute(_NEW_IMMUTABLE_COLUMNS_TRIGGER)


def downgrade():
    op.execute("DROP TRIGGER IF EXISTS trg_relationship_immutable_columns")
    op.execute("DROP INDEX IF EXISTS uq_relationship_user_pair")

    with op.batch_alter_table("relationship", schema=None) as batch_op:
        batch_op.drop_constraint("ck_relationship_currency_format", type_="check")
        batch_op.drop_column("currency_code")

    op.execute(_UQ_RELATIONSHIP_USER_PAIR)
    op.execute(_OLD_IMMUTABLE_COLUMNS_TRIGGER)
