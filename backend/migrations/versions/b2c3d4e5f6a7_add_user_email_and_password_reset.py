"""add user.email and password_reset_token table

Revision ID: b2c3d4e5f6a7
Revises: a1b2c3d4e5f6
Create Date: 2026-07-14 00:00:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'b2c3d4e5f6a7'
down_revision = 'a1b2c3d4e5f6'
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table('user', schema=None) as batch_op:
        batch_op.add_column(sa.Column('email', sa.String(length=255), nullable=True))
    # Unique index rather than an inline constraint: on SQLite a unique
    # index is equivalent to a unique constraint, and create_index lands
    # reliably where a batch-recreated constraint does not (see CLAUDE.md).
    op.create_index('uq_user_email', 'user', ['email'], unique=True)

    op.create_table(
        'password_reset_token',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('user_id', sa.Integer(), nullable=False),
        sa.Column('token_hash', sa.String(length=64), nullable=False),
        sa.Column(
            'created_at',
            sa.DateTime(timezone=True),
            server_default=sa.text('(CURRENT_TIMESTAMP)'),
            nullable=False,
        ),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('used_at', sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(['user_id'], ['user.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_password_reset_token_token_hash'),
        'password_reset_token',
        ['token_hash'],
        unique=True,
    )


def downgrade():
    op.drop_index(op.f('ix_password_reset_token_token_hash'), table_name='password_reset_token')
    op.drop_table('password_reset_token')
    op.drop_index('uq_user_email', table_name='user')
    with op.batch_alter_table('user', schema=None) as batch_op:
        batch_op.drop_column('email')
