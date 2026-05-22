"""relationship: rename party columns, add rejected status, swap uniqueness

Revision ID: 2d79c4789eb6
Revises: 278782a3e79c
Create Date: 2026-05-22 14:30:00.000000

The Phase 1 ``relationship`` table stored its two parties as
``user_a_id`` / ``user_b_id`` with a CHECK that enforced ``user_a_id <
user_b_id`` and a UNIQUE on that pair. Phase 2 records the inviting and
invited parties explicitly (so we can identify who must accept) and
shifts symmetric uniqueness onto a partial expression index that uses
``MIN``/``MAX`` of the pair: either-direction duplicates among active
rows are blocked, but rejected rows are excluded from the index so a
prior rejection does not prevent a re-invite.

SQLite cannot ALTER TABLE to drop a CHECK constraint or rename a column
that participates in one, so the migration uses ``op.batch_alter_table``
with an explicit ``copy_from`` describing the Phase 1 shape. That tells
Alembic exactly what the source table looks like without depending on
reflection (which on SQLite drops named-CHECK metadata). Alembic then
synthesises a "move and copy" sequence: create the new table, copy
rows, drop old, rename in. The partial expression index is created
afterwards via raw SQL because Alembic's ``op.create_index`` does not
support both expression-based columns and a partial WHERE clause on
SQLite in a single call.
"""

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '2d79c4789eb6'
down_revision = '278782a3e79c'
branch_labels = None
depends_on = None


old_status_enum = sa.Enum('pending', 'accepted', name='relationship_status')
new_status_enum = sa.Enum('pending', 'accepted', 'rejected', name='relationship_status')


def _phase1_relationship_table() -> sa.Table:
    metadata = sa.MetaData()
    return sa.Table(
        "relationship",
        metadata,
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "user_a_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False
        ),
        sa.Column(
            "user_b_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False
        ),
        sa.Column(
            "status",
            old_status_enum,
            nullable=False,
            server_default="pending",
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
        ),
        sa.CheckConstraint("user_a_id < user_b_id", name="ck_relationship_user_order"),
        sa.UniqueConstraint("user_a_id", "user_b_id", name="uq_relationship_user_pair"),
    )


def _phase2_relationship_table() -> sa.Table:
    metadata = sa.MetaData()
    return sa.Table(
        "relationship",
        metadata,
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "inviting_user_id",
            sa.Integer(),
            sa.ForeignKey("user.id"),
            nullable=False,
        ),
        sa.Column(
            "invited_user_id",
            sa.Integer(),
            sa.ForeignKey("user.id"),
            nullable=False,
        ),
        sa.Column(
            "status",
            new_status_enum,
            nullable=False,
            server_default="pending",
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
        ),
        sa.CheckConstraint(
            "inviting_user_id != invited_user_id",
            name="ck_relationship_distinct_parties",
        ),
    )


def upgrade():
    with op.batch_alter_table(
        "relationship",
        copy_from=_phase1_relationship_table(),
        recreate="always",
    ) as batch_op:
        batch_op.drop_constraint("ck_relationship_user_order", type_="check")
        batch_op.drop_constraint("uq_relationship_user_pair", type_="unique")
        batch_op.alter_column("user_a_id", new_column_name="inviting_user_id")
        batch_op.alter_column("user_b_id", new_column_name="invited_user_id")
        batch_op.alter_column(
            "status",
            existing_type=old_status_enum,
            type_=new_status_enum,
            existing_nullable=False,
            existing_server_default="pending",
        )
        batch_op.create_check_constraint(
            "ck_relationship_distinct_parties",
            "inviting_user_id != invited_user_id",
        )

    op.execute(
        "CREATE UNIQUE INDEX uq_relationship_user_pair "
        "ON relationship ("
        "MIN(inviting_user_id, invited_user_id), "
        "MAX(inviting_user_id, invited_user_id)"
        ") WHERE status != 'rejected'"
    )


def downgrade():
    op.execute("DROP INDEX uq_relationship_user_pair")

    with op.batch_alter_table(
        "relationship",
        copy_from=_phase2_relationship_table(),
        recreate="always",
    ) as batch_op:
        batch_op.drop_constraint("ck_relationship_distinct_parties", type_="check")
        batch_op.alter_column(
            "status",
            existing_type=new_status_enum,
            type_=old_status_enum,
            existing_nullable=False,
            existing_server_default="pending",
        )
        batch_op.alter_column("invited_user_id", new_column_name="user_b_id")
        batch_op.alter_column("inviting_user_id", new_column_name="user_a_id")
        batch_op.create_check_constraint(
            "ck_relationship_user_order",
            "user_a_id < user_b_id",
        )

    op.create_index(
        "uq_relationship_user_pair",
        "relationship",
        ["user_a_id", "user_b_id"],
        unique=True,
    )
