"""add device_token

Revision ID: d3f1a7c9b204
Revises: beec85febafc
Create Date: 2026-07-11 04:20:10.512334

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'd3f1a7c9b204'
down_revision = 'beec85febafc'
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "device_token",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("token", sa.String(length=512), nullable=False),
        sa.Column("platform", sa.String(length=16), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "last_seen_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["user_id"], ["user.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("token", name="uq_device_token_token"),
    )
    op.create_index("ix_device_token_user_id", "device_token", ["user_id"])


def downgrade():
    op.drop_index("ix_device_token_user_id", table_name="device_token")
    op.drop_table("device_token")
