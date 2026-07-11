"""add user.deleted_at

Revision ID: beec85febafc
Revises: c4d8f0a91e22
Create Date: 2026-07-10 17:52:40.753652

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'beec85febafc'
down_revision = 'c4d8f0a91e22'
branch_labels = None
depends_on = None


def upgrade():
    # SQLite supports ADD COLUMN natively, so no table rebuild is needed
    # (avoids batch mode and its constraint-reflection pitfalls).
    op.add_column("user", sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True))


def downgrade():
    with op.batch_alter_table("user", schema=None) as batch_op:
        batch_op.drop_column("deleted_at")
