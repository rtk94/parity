"""password_reset_token: attempt counter, non-unique code hash

Reset credentials moved from a 43-character URL-safe token to an 8-digit
numeric code whose digest is scoped to the owning user. Two consequences
land here:

* ``attempts`` gives each code a guess budget, which is what makes a
  short code safe (see services/password_reset.py).
* the ``token_hash`` index drops its uniqueness. It was only ever there
  because a global token had to be globally unique; a user-scoped digest
  is not, and a repeat draw for one account must supersede the old row
  rather than raise.

Both are additive/index-only, so plain ``add_column`` and
``drop_index``/``create_index`` work on SQLite — no ``batch_alter_table``
move-and-copy, and so no risk of silently dropping table constraints.

Revision ID: d5e6f7a8b9c0
Revises: c3d4e5f6a7b8
Create Date: 2026-09-17 00:00:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'd5e6f7a8b9c0'
down_revision = 'c3d4e5f6a7b8'
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        'password_reset_token',
        sa.Column('attempts', sa.Integer(), nullable=False, server_default=sa.text('0')),
    )
    op.drop_index(
        op.f('ix_password_reset_token_token_hash'),
        table_name='password_reset_token',
    )
    op.create_index(
        op.f('ix_password_reset_token_token_hash'),
        'password_reset_token',
        ['token_hash'],
        unique=False,
    )
    # Any code minted under the old scheme is a long token that the new
    # confirm path can no longer match. Retire them so nobody is left
    # holding a credential that silently never works.
    op.execute(
        "UPDATE password_reset_token "
        "SET used_at = CURRENT_TIMESTAMP "
        "WHERE used_at IS NULL"
    )


def downgrade():
    op.drop_index(
        op.f('ix_password_reset_token_token_hash'),
        table_name='password_reset_token',
    )
    op.create_index(
        op.f('ix_password_reset_token_token_hash'),
        'password_reset_token',
        ['token_hash'],
        unique=True,
    )
    op.drop_column('password_reset_token', 'attempts')
