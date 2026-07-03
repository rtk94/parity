"""CLI commands for Parity backend."""

from datetime import UTC, datetime

import click
from flask import Blueprint

from app.extensions import db
from app.models import AuthToken

cli_bp = Blueprint("cli", __name__, cli_group=None)


@cli_bp.cli.command("cleanup-tokens")
def cleanup_tokens() -> None:
    """Remove expired and revoked auth tokens from the database."""
    now = datetime.now(UTC)
    deleted = (
        db.session.query(AuthToken)
        .filter((AuthToken.expires_at < now) | (AuthToken.revoked_at.isnot(None)))
        .delete()
    )
    db.session.commit()
    click.echo(f"Deleted {deleted} expired/revoked tokens.")
