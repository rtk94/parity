"""add recurring expense tables

Revision ID: a1b2c3d4e5f6
Revises: d3f1a7c9b204
Create Date: 2026-07-14 00:00:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'a1b2c3d4e5f6'
down_revision = 'd3f1a7c9b204'
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        'recurring_expense',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('relationship_id', sa.Integer(), nullable=False),
        sa.Column('payer_user_id', sa.Integer(), nullable=False),
        sa.Column('total_cents', sa.Integer(), nullable=False),
        sa.Column('description', sa.String(length=512), nullable=False),
        sa.Column('category', sa.String(length=64), nullable=True),
        sa.Column(
            'interval',
            sa.Enum('daily', 'weekly', 'monthly', name='recurring_interval'),
            nullable=False,
        ),
        sa.Column('next_run_on', sa.Date(), nullable=False),
        sa.Column('active', sa.Boolean(), server_default=sa.text('1'), nullable=False),
        sa.Column('created_by_user_id', sa.Integer(), nullable=False),
        sa.Column(
            'created_at',
            sa.DateTime(timezone=True),
            server_default=sa.text('(CURRENT_TIMESTAMP)'),
            nullable=False,
        ),
        sa.Column('last_run_at', sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint('total_cents > 0', name='ck_recurring_expense_total_positive'),
        sa.ForeignKeyConstraint(['created_by_user_id'], ['user.id'], ),
        sa.ForeignKeyConstraint(['payer_user_id'], ['user.id'], ),
        sa.ForeignKeyConstraint(['relationship_id'], ['relationship.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_table(
        'recurring_expense_share',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('recurring_expense_id', sa.Integer(), nullable=False),
        sa.Column('user_id', sa.Integer(), nullable=False),
        sa.Column('amount_cents', sa.Integer(), nullable=False),
        sa.CheckConstraint('amount_cents > 0', name='ck_recurring_expense_share_amount_positive'),
        sa.ForeignKeyConstraint(['recurring_expense_id'], ['recurring_expense.id'], ),
        sa.ForeignKeyConstraint(['user_id'], ['user.id'], ),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint(
            'recurring_expense_id',
            'user_id',
            name='uq_recurring_expense_share_recurring_user',
        ),
    )


def downgrade():
    op.drop_table('recurring_expense_share')
    op.drop_table('recurring_expense')
