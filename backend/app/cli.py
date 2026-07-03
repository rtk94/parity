"""CLI commands for Parity backend."""

from __future__ import annotations

import secrets

import click
from flask import Blueprint
from sqlalchemy import select

from app.auth.security import hash_password
from app.extensions import db
from app.models import AuthToken, User
from app.services import admin as admin_service

cli_bp = Blueprint("cli", __name__, cli_group=None)


@cli_bp.cli.command("cleanup-tokens")
def cleanup_tokens() -> None:
    """Remove expired and revoked auth tokens from the database."""
    deleted = admin_service.cleanup_tokens()
    click.echo(f"Deleted {deleted} expired/revoked tokens.")


@cli_bp.cli.command("create-admin")
@click.argument("username")
@click.option("--display-name", default=None, help="Display name; defaults to the username.")
@click.option(
    "--rotate-key",
    is_flag=True,
    default=False,
    help="Regenerate the access key for an existing admin user and revoke its sessions.",
)
def create_admin(username: str, display_name: str | None, rotate_key: bool) -> None:
    """Create the system-administrator user and print its access key.

    The key is a machine-generated 256-bit secret used in place of a
    password on the normal login flow. It is printed exactly once —
    store it somewhere safe. Only someone with shell access to the
    server can run this command, which is what scopes the admin
    account to the operator.
    """
    existing = db.session.execute(
        select(User).where(User.username == username)
    ).scalar_one_or_none()

    key = secrets.token_urlsafe(32)

    if existing is not None:
        if not (existing.is_admin and rotate_key):
            raise click.ClickException(
                f"User '{username}' already exists. Use --rotate-key on an existing "
                "admin user to generate a fresh key."
            )
        existing.password_hash = hash_password(key)
        # A rotated key invalidates every open session for the account.
        db.session.execute(
            AuthToken.__table__.update()
            .where(AuthToken.user_id == existing.id, AuthToken.revoked_at.is_(None))
            .values(revoked_at=db.func.now())
        )
        db.session.commit()
        click.echo(f"Rotated access key for admin '{username}'. Existing sessions revoked.")
    else:
        user = User(
            username=username,
            password_hash=hash_password(key),
            display_name=(display_name or username).strip(),
            is_admin=True,
        )
        db.session.add(user)
        db.session.commit()
        click.echo(f"Created admin user '{username}'.")

    click.echo("Access key (shown once, sign in with it as the password):")
    click.echo(key)


@cli_bp.cli.command("reset-ledger")
@click.option(
    "--yes-i-mean-it",
    is_flag=True,
    default=False,
    help="Required. Erases every expense, payment, share, and comment.",
)
def reset_ledger(yes_i_mean_it: bool) -> None:
    """Erase all ledger entries (keeps users, relationships, audit log)."""
    if not yes_i_mean_it:
        raise click.ClickException(
            "This erases every expense, payment, share, and comment. "
            "Re-run with --yes-i-mean-it to proceed."
        )

    # Attribute the reset to an admin user when one exists so the
    # audit log records the acting account; the CLI itself is already
    # operator-only.
    admin_user = (
        db.session.execute(select(User).where(User.is_admin.is_(True)).order_by(User.id))
        .scalars()
        .first()
    )

    counts = admin_service.reset_ledger(actor_user_id=admin_user.id if admin_user else None)
    click.echo(
        "Ledger reset: deleted "
        f"{counts['expenses']} expenses, {counts['expense_shares']} shares, "
        f"{counts['payments']} payments, {counts['comments']} comments."
    )
