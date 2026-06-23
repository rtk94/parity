"""multi_currency index

Revision ID: 6cdaa625dc51
Revises: ba4efb8a9c2b
Create Date: 2026-06-23 03:27:26.346674

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '6cdaa625dc51'
down_revision = 'ba4efb8a9c2b'
branch_labels = None
depends_on = None


def upgrade():
    op.execute("DROP INDEX uq_relationship_user_pair")
    op.execute("CREATE UNIQUE INDEX uq_relationship_user_pair ON relationship (MIN(inviting_user_id, invited_user_id), MAX(inviting_user_id, invited_user_id), currency_code) WHERE status != 'rejected'")


def downgrade():
    op.execute("DROP INDEX uq_relationship_user_pair")
    op.execute("CREATE UNIQUE INDEX uq_relationship_user_pair ON relationship (MIN(inviting_user_id, invited_user_id), MAX(inviting_user_id, invited_user_id)) WHERE status != 'rejected'")
