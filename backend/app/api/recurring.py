"""Recurring-expense endpoints: create, list, get, update, delete.

Templates that generate pending expenses on a schedule. The entries
they produce flow through the normal expense endpoints; these routes
only manage the templates themselves.
"""

from __future__ import annotations

from flask import Blueprint, g, request

from app.api._helpers import (
    int_query_arg,
    json_body,
    paginated_response,
    pagination_args,
    translates_service_errors,
)
from app.api._rate_limits import authed_write_limit
from app.api._serializers import serialize_recurring_expense
from app.auth.decorators import login_required
from app.services import BadRequestError
from app.services import recurring as recurring_service

recurring_bp = Blueprint("recurring", __name__, url_prefix="/api/v1/recurring")


def _active_query_arg() -> bool | None:
    raw = request.args.get("active")
    if raw is None or raw == "":
        return None
    if raw == "true":
        return True
    if raw == "false":
        return False
    raise BadRequestError(message="active must be 'true' or 'false'.")


@recurring_bp.post("")
@login_required
@authed_write_limit()
@translates_service_errors
def create_recurring():
    template = recurring_service.create(g.current_user, json_body())
    return serialize_recurring_expense(template), 201


@recurring_bp.get("")
@login_required
@translates_service_errors
def list_recurring():
    relationship_id = int_query_arg("relationship_id")
    active = _active_query_arg()
    limit, offset = pagination_args()
    items, total = recurring_service.list_for_user(
        g.current_user,
        relationship_id=relationship_id,
        active=active,
        limit=limit,
        offset=offset,
    )
    return (
        paginated_response([serialize_recurring_expense(t) for t in items], total, limit, offset),
        200,
    )


@recurring_bp.get("/<int:recurring_id>")
@login_required
@translates_service_errors
def get_recurring(recurring_id: int):
    template = recurring_service.get_for_user(g.current_user, recurring_id)
    return serialize_recurring_expense(template), 200


@recurring_bp.patch("/<int:recurring_id>")
@login_required
@authed_write_limit()
@translates_service_errors
def update_recurring(recurring_id: int):
    template = recurring_service.update(g.current_user, recurring_id, json_body())
    return serialize_recurring_expense(template), 200


@recurring_bp.delete("/<int:recurring_id>")
@login_required
@authed_write_limit()
@translates_service_errors
def delete_recurring(recurring_id: int):
    recurring_service.delete(g.current_user, recurring_id)
    return "", 204
