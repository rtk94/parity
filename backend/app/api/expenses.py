"""Expense endpoints: create, list, get, confirm, discard, reverse."""

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
from app.api._serializers import serialize_expense
from app.auth.decorators import login_required
from app.services import expenses as expenses_service

expenses_bp = Blueprint("expenses", __name__, url_prefix="/api/v1/expenses")


@expenses_bp.post("")
@login_required
@authed_write_limit()
@translates_service_errors
def create_expense():
    expense = expenses_service.create(g.current_user, json_body())
    return serialize_expense(expense), 201


@expenses_bp.get("")
@login_required
@translates_service_errors
def list_expenses():
    relationship_id = int_query_arg("relationship_id")
    status = request.args.get("status")
    limit, offset = pagination_args()
    items, total = expenses_service.list_for_user(
        g.current_user,
        relationship_id=relationship_id,
        status=status,
        limit=limit,
        offset=offset,
    )
    return paginated_response([serialize_expense(e) for e in items], total, limit, offset), 200


@expenses_bp.get("/<int:expense_id>")
@login_required
@translates_service_errors
def get_expense(expense_id: int):
    expense = expenses_service.get_for_user(g.current_user, expense_id)
    return serialize_expense(expense), 200


@expenses_bp.post("/<int:expense_id>/confirm")
@login_required
@authed_write_limit()
@translates_service_errors
def confirm_expense(expense_id: int):
    expense = expenses_service.confirm(g.current_user, expense_id)
    return serialize_expense(expense), 200


@expenses_bp.post("/<int:expense_id>/discard")
@login_required
@authed_write_limit()
@translates_service_errors
def discard_expense(expense_id: int):
    expense = expenses_service.discard(g.current_user, expense_id, json_body())
    return serialize_expense(expense), 200


@expenses_bp.post("/<int:expense_id>/reverse")
@login_required
@authed_write_limit()
@translates_service_errors
def reverse_expense(expense_id: int):
    reversal = expenses_service.reverse(g.current_user, expense_id)
    return serialize_expense(reversal), 201


@expenses_bp.post("/<int:expense_id>/comments")
@login_required
@authed_write_limit()
@translates_service_errors
def create_comment(expense_id: int):
    from app.api._serializers import serialize_comment
    from app.services import comments as comments_service
    comment = comments_service.create_for_expense(g.current_user, expense_id, json_body())
    return serialize_comment(comment), 201


@expenses_bp.get("/<int:expense_id>/comments")
@login_required
@translates_service_errors
def list_comments(expense_id: int):
    from app.api._serializers import serialize_comment
    from app.services import comments as comments_service
    comments = comments_service.list_for_expense(g.current_user, expense_id)
    return {"items": [serialize_comment(c) for c in comments]}, 200
