"""Attachment service: upload, list, fetch, and delete expense attachments.

Metadata is validated and stored here; the bytes go to the configured
object store (see ADR-0003). Authorization mirrors the rest of the
ledger — only a party to the expense's relationship may touch its
attachments, and non-parties get 404.
"""

from __future__ import annotations

import contextlib
import hashlib
from uuid import uuid4

from flask import current_app
from sqlalchemy import select
from werkzeug.utils import secure_filename

from app.extensions import db
from app.models import Attachment, Expense, User
from app.services import ForbiddenError, NotFoundError, ValidationError
from app.services import expenses as expenses_service
from app.services.audit import log_action
from app.services.object_store import ObjectNotFound

# Receipts are images or PDFs. Kept deliberately small; widen only with a
# concrete need.
ALLOWED_CONTENT_TYPES = frozenset(
    {
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/heic",
        "application/pdf",
    }
)


def _max_bytes() -> int:
    return current_app.config["ATTACHMENT_MAX_BYTES"]


def _store():
    return current_app.extensions["object_store"]


def create(
    user: User,
    expense_id: int,
    *,
    filename: str | None,
    content_type: str | None,
    data: bytes,
) -> Attachment:
    # Authorization + existence: a non-party (or unknown expense) gets 404.
    expense = expenses_service.get_for_user(user, expense_id)

    if not data:
        raise ValidationError("empty_file", "The uploaded file is empty.")
    if len(data) > _max_bytes():
        raise ValidationError(
            "file_too_large",
            "The uploaded file exceeds the size limit.",
            details={"max_bytes": _max_bytes()},
        )
    if not isinstance(content_type, str) or content_type not in ALLOWED_CONTENT_TYPES:
        raise ValidationError(
            "unsupported_type",
            "Unsupported file type.",
            details={"allowed": sorted(ALLOWED_CONTENT_TYPES)},
        )

    safe_name = secure_filename(filename or "") or "attachment"
    storage_key = f"expense/{expense.id}/{uuid4().hex}"

    # Write the bytes first, then record the row. If the DB write fails,
    # best-effort delete the orphaned object (ADR-0003 accepts rare
    # orphans over a two-phase commit).
    _store().put(storage_key, data, content_type)
    attachment = Attachment(
        expense_id=expense.id,
        uploaded_by_user_id=user.id,
        filename=safe_name,
        content_type=content_type,
        size_bytes=len(data),
        checksum_sha256=hashlib.sha256(data).hexdigest(),
        storage_key=storage_key,
    )
    db.session.add(attachment)
    try:
        db.session.flush()
        log_action(user.id, "create", "attachment", attachment.id)
        db.session.commit()
    except Exception:
        db.session.rollback()
        with contextlib.suppress(Exception):
            _store().delete(storage_key)
        raise
    db.session.refresh(attachment)
    return attachment


def list_for_expense(user: User, expense_id: int) -> list[Attachment]:
    expenses_service.get_for_user(user, expense_id)  # authz / 404
    stmt = (
        select(Attachment)
        .where(Attachment.expense_id == expense_id)
        .order_by(Attachment.created_at.asc(), Attachment.id.asc())
    )
    return list(db.session.execute(stmt).scalars().all())


def get_for_user(user: User, attachment_id: int) -> Attachment:
    attachment = db.session.get(Attachment, attachment_id)
    if attachment is None:
        raise NotFoundError("not_found", "Not found.")
    # Resolve through the expense to the relationship parties (404 if not a party).
    expenses_service.get_for_user(user, attachment.expense_id)
    return attachment


def get_bytes(attachment: Attachment) -> bytes:
    try:
        return _store().get(attachment.storage_key)
    except ObjectNotFound as exc:
        # Row without an object (compensation gap). Surface as a 404.
        raise NotFoundError("not_found", "Attachment content is unavailable.") from exc


def delete(user: User, attachment_id: int) -> None:
    attachment = get_for_user(user, attachment_id)
    if attachment.uploaded_by_user_id != user.id:
        raise ForbiddenError("not_uploader", "Only the uploader can delete this attachment.")
    storage_key = attachment.storage_key
    log_action(user.id, "delete", "attachment", attachment.id)
    db.session.delete(attachment)
    db.session.commit()
    # Best-effort object cleanup; a leftover object is harmless.
    with contextlib.suppress(Exception):
        _store().delete(storage_key)


def attachments_for_relationship_ids(rel_ids: list[int]) -> list[Attachment]:
    """All attachments on expenses in the given relationships (for export)."""
    if not rel_ids:
        return []
    expense_ids = select(Expense.id).where(Expense.relationship_id.in_(rel_ids))
    stmt = select(Attachment).where(Attachment.expense_id.in_(expense_ids))
    return list(db.session.execute(stmt).scalars().all())
