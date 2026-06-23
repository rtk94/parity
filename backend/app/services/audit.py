"""Audit log service."""

from app.extensions import db
from app.models.audit import AuditLog


def log_action(
    user_id: int, action: str, target_type: str, target_id: int, details: str | None = None
) -> None:
    """Log an action to the audit log."""
    audit_log = AuditLog(
        user_id=user_id,
        action=action,
        target_type=target_type,
        target_id=target_id,
        details=details,
    )
    db.session.add(audit_log)
