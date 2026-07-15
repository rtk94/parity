"""Attachment endpoints: upload, list, download, delete.

Files are attached to expenses; only a party to the expense's
relationship may upload, list, or download them, and only the uploader
may delete. Bytes live in object storage (see ADR-0003); these routes
handle the multipart upload and the download stream.
"""

from __future__ import annotations

from flask import Blueprint, Response, g, request

from app.api._helpers import translates_service_errors
from app.api._rate_limits import authed_write_limit
from app.api._serializers import serialize_attachment
from app.auth.decorators import login_required
from app.services import BadRequestError
from app.services import attachments as attachments_service

attachments_bp = Blueprint("attachments", __name__, url_prefix="/api/v1")


@attachments_bp.post("/expenses/<int:expense_id>/attachments")
@login_required
@authed_write_limit()
@translates_service_errors
def upload_attachment(expense_id: int):
    file = request.files.get("file")
    if file is None:
        raise BadRequestError(message="A multipart 'file' field is required.")
    # Bound the read at the limit + 1 byte so an oversized upload can't
    # exhaust memory; the service rejects anything over the cap.
    max_bytes = attachments_service._max_bytes()
    data = file.stream.read(max_bytes + 1)
    attachment = attachments_service.create(
        g.current_user,
        expense_id,
        filename=file.filename,
        content_type=file.mimetype,
        data=data,
    )
    return serialize_attachment(attachment), 201


@attachments_bp.get("/expenses/<int:expense_id>/attachments")
@login_required
@translates_service_errors
def list_attachments(expense_id: int):
    items = attachments_service.list_for_expense(g.current_user, expense_id)
    return {"items": [serialize_attachment(a) for a in items]}, 200


@attachments_bp.get("/attachments/<int:attachment_id>")
@login_required
@translates_service_errors
def download_attachment(attachment_id: int):
    attachment = attachments_service.get_for_user(g.current_user, attachment_id)
    data = attachments_service.get_bytes(attachment)
    return Response(
        data,
        status=200,
        content_type=attachment.content_type,
        headers={
            "Content-Disposition": f'attachment; filename="{attachment.filename}"',
            "Content-Length": str(len(data)),
        },
    )


@attachments_bp.delete("/attachments/<int:attachment_id>")
@login_required
@authed_write_limit()
@translates_service_errors
def delete_attachment(attachment_id: int):
    attachments_service.delete(g.current_user, attachment_id)
    return "", 204
