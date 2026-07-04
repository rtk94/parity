"""add user.is_admin

Revision ID: 7e5a2c9d41bb
Revises: 6cdaa625dc51
Create Date: 2026-07-03 17:00:00.000000

"""

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision = "7e5a2c9d41bb"
down_revision = "6cdaa625dc51"
branch_labels = None
depends_on = None


def upgrade():
    # Plain ADD COLUMN works on SQLite; no batch move-and-copy needed.
    op.add_column(
        "user",
        sa.Column("is_admin", sa.Boolean(), nullable=False, server_default=sa.text("0")),
    )


def downgrade():
    with op.batch_alter_table("user", schema=None) as batch_op:
        batch_op.drop_column("is_admin")
