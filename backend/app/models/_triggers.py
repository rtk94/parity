"""DB-level immutability triggers for the ledger.

The Parity ledger is immutable: confirmed and discarded rows are never
updated or deleted, and the columns that identify what a ledger entry
*is* (parties, amount, description, creation metadata, reversal
linkage) never change after the row is created. Application code
already enforces these invariants via the service layer. The triggers
in this module are a DB-level backstop against bugs in service code
and against direct SQL access; they should never fire in normal
application operation.

The triggers live in two places — kept deliberately in sync:

1. ``TRIGGER_STATEMENTS`` plus any later evolutions (currently
   ``PHASE5_TRIGGER_EVOLUTION``) are installed on every fresh schema
   via the SQLAlchemy ``after_create`` listener registered at the
   bottom of this module. ``db.create_all()`` (used by the test
   fixture) walks ``metadata.create_all`` which fires the listener, so
   trigger tests exercise the real DDL.
2. The Alembic immutability-triggers migration imports
   ``TRIGGER_STATEMENTS`` and emits those statements via
   ``op.execute``. Later phases (Phase 5+) self-contain their trigger
   evolutions inside the relevant migration. ``TRIGGER_STATEMENTS`` is
   frozen as the Phase 3 snapshot so the Phase 3 migration stays
   correct on a fresh database upgrade.

If you change a trigger here you must write a new migration that drops
the old triggers and re-creates them; the model layer and the migration
chain must agree.
"""

from __future__ import annotations

from sqlalchemy import event, text
from sqlalchemy.engine import Connection
from sqlalchemy.sql.schema import MetaData

from app.extensions import db

# --- Trigger DDL ------------------------------------------------------
#
# Each entry is a complete CREATE TRIGGER statement. SQLite-specific
# syntax (``RAISE(ABORT, ...)``) is used; the trigger error surfaces to
# SQLAlchemy as ``sqlalchemy.exc.IntegrityError``.
#
# Naming: ``trg_<table>_<purpose>``. Error messages are prefixed with
# ``<table>_immutable:`` so they are easy to grep for in logs.
#
# For nullable columns we use ``NEW.col IS NOT OLD.col`` (NULL-safe
# inequality) rather than ``!=``. ``IS NOT`` works for non-null columns
# too, so the file uses it uniformly for consistency.

TRIGGER_STATEMENTS: list[str] = [
    # expense ----------------------------------------------------------
    """
    CREATE TRIGGER trg_expense_no_delete
    BEFORE DELETE ON expense
    FOR EACH ROW
    BEGIN
        SELECT RAISE(ABORT, 'expense_immutable: delete forbidden');
    END
    """,
    """
    CREATE TRIGGER trg_expense_no_update_terminal
    BEFORE UPDATE ON expense
    FOR EACH ROW
    WHEN OLD.status != 'pending'
    BEGIN
        SELECT RAISE(ABORT, 'expense_immutable: update on terminal row');
    END
    """,
    """
    CREATE TRIGGER trg_expense_immutable_columns
    BEFORE UPDATE ON expense
    FOR EACH ROW
    WHEN
        NEW.payer_user_id IS NOT OLD.payer_user_id
        OR NEW.relationship_id IS NOT OLD.relationship_id
        OR NEW.total_cents IS NOT OLD.total_cents
        OR NEW.description IS NOT OLD.description
        OR NEW.created_by_user_id IS NOT OLD.created_by_user_id
        OR NEW.created_at IS NOT OLD.created_at
        OR NEW.reverses_expense_id IS NOT OLD.reverses_expense_id
    BEGIN
        SELECT RAISE(ABORT, 'expense_immutable: protected column changed');
    END
    """,
    # expense_share — insert-only ------------------------------------
    """
    CREATE TRIGGER trg_expense_share_no_update
    BEFORE UPDATE ON expense_share
    FOR EACH ROW
    BEGIN
        SELECT RAISE(ABORT, 'expense_share_immutable: update forbidden');
    END
    """,
    """
    CREATE TRIGGER trg_expense_share_no_delete
    BEFORE DELETE ON expense_share
    FOR EACH ROW
    BEGIN
        SELECT RAISE(ABORT, 'expense_share_immutable: delete forbidden');
    END
    """,
    # payment ----------------------------------------------------------
    """
    CREATE TRIGGER trg_payment_no_delete
    BEFORE DELETE ON payment
    FOR EACH ROW
    BEGIN
        SELECT RAISE(ABORT, 'payment_immutable: delete forbidden');
    END
    """,
    """
    CREATE TRIGGER trg_payment_no_update_terminal
    BEFORE UPDATE ON payment
    FOR EACH ROW
    WHEN OLD.status != 'pending'
    BEGIN
        SELECT RAISE(ABORT, 'payment_immutable: update on terminal row');
    END
    """,
    """
    CREATE TRIGGER trg_payment_immutable_columns
    BEFORE UPDATE ON payment
    FOR EACH ROW
    WHEN
        NEW.from_user_id IS NOT OLD.from_user_id
        OR NEW.to_user_id IS NOT OLD.to_user_id
        OR NEW.relationship_id IS NOT OLD.relationship_id
        OR NEW.amount_cents IS NOT OLD.amount_cents
        OR NEW.description IS NOT OLD.description
        OR NEW.created_by_user_id IS NOT OLD.created_by_user_id
        OR NEW.created_at IS NOT OLD.created_at
        OR NEW.reverses_payment_id IS NOT OLD.reverses_payment_id
    BEGIN
        SELECT RAISE(ABORT, 'payment_immutable: protected column changed');
    END
    """,
    # relationship — accepted and rejected are terminal ----------------
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
    """
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
    """,
]


# Phase 5 evolved ``trg_relationship_immutable_columns`` to also
# protect ``currency_code``. The migration that adds the column drops
# the Phase 3 trigger and re-creates it with the wider WHEN clause;
# the same drop-and-recreate runs here so a fresh schema built via
# ``db.create_all()`` ends up with the current trigger shape.
PHASE5_TRIGGER_EVOLUTION: list[str] = [
    "DROP TRIGGER IF EXISTS trg_relationship_immutable_columns",
    """
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
    """,
]


# Drop statements in reverse creation order so a downgrade tears
# triggers down in the same shape as a fresh create-then-drop would.
TRIGGER_NAMES_REVERSED: list[str] = [
    "trg_relationship_immutable_columns",
    "trg_relationship_no_update_terminal",
    "trg_relationship_no_delete",
    "trg_payment_immutable_columns",
    "trg_payment_no_update_terminal",
    "trg_payment_no_delete",
    "trg_expense_share_no_delete",
    "trg_expense_share_no_update",
    "trg_expense_immutable_columns",
    "trg_expense_no_update_terminal",
    "trg_expense_no_delete",
]


DROP_STATEMENTS: list[str] = [f"DROP TRIGGER IF EXISTS {name}" for name in TRIGGER_NAMES_REVERSED]


def _install_triggers_on_create(
    target: MetaData,  # noqa: ARG001 — event signature
    connection: Connection,
    **kw,  # noqa: ARG001
) -> None:
    """Install triggers after ``metadata.create_all`` builds the schema.

    The triggers use SQLite-specific syntax (``RAISE(ABORT, ...)``);
    silently skip on other dialects. We never deploy on anything else.
    """
    if connection.dialect.name != "sqlite":
        return
    for stmt in TRIGGER_STATEMENTS:
        connection.execute(text(stmt))
    for stmt in PHASE5_TRIGGER_EVOLUTION:
        connection.execute(text(stmt))


event.listen(db.metadata, "after_create", _install_triggers_on_create)
