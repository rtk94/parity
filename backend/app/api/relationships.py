"""Relationship endpoints: invite, list, get, accept, reject, balance."""

from __future__ import annotations

from flask import Blueprint, g, request

from app.api._helpers import (
    json_body,
    paginated_response,
    pagination_args,
    translates_service_errors,
)
from app.api._rate_limits import authed_write_limit
from app.api._serializers import (
    serialize_balance_view,
    serialize_expense,
    serialize_payment,
    serialize_relationship,
)
from app.auth.decorators import login_required
from app.models import Expense
from app.services import relationships as relationships_service
from app.services.balance import compute_balance

relationships_bp = Blueprint("relationships", __name__, url_prefix="/api/v1/relationships")


@relationships_bp.post("")
@login_required
@authed_write_limit()
@translates_service_errors
def invite():
    rel, entry = relationships_service.invite_by_username(g.current_user, json_body())
    if entry is None:
        return serialize_relationship(rel), 201
    if isinstance(entry, Expense):
        return {
            "relationship": serialize_relationship(rel),
            "expense": serialize_expense(entry),
        }, 201
    return {
        "relationship": serialize_relationship(rel),
        "payment": serialize_payment(entry),
    }, 201


@relationships_bp.get("")
@login_required
@translates_service_errors
def list_relationships():
    status = request.args.get("status")
    limit, offset = pagination_args()
    items, total = relationships_service.list_for_user(
        g.current_user, status=status, limit=limit, offset=offset
    )
    return paginated_response(
        [serialize_relationship(rel) for rel in items], total, limit, offset
    ), 200


@relationships_bp.get("/<int:relationship_id>")
@login_required
@translates_service_errors
def get_relationship(relationship_id: int):
    rel = relationships_service.get_for_user(g.current_user, relationship_id)
    return serialize_relationship(rel), 200


@relationships_bp.post("/<int:relationship_id>/accept")
@login_required
@authed_write_limit()
@translates_service_errors
def accept_relationship(relationship_id: int):
    rel = relationships_service.accept(g.current_user, relationship_id)
    return serialize_relationship(rel), 200


@relationships_bp.post("/<int:relationship_id>/reject")
@login_required
@authed_write_limit()
@translates_service_errors
def reject_relationship(relationship_id: int):
    rel = relationships_service.reject(g.current_user, relationship_id)
    return serialize_relationship(rel), 200


@relationships_bp.get("/<int:relationship_id>/balance")
@login_required
@translates_service_errors
def get_balance(relationship_id: int):
    rel = relationships_service.get_for_user(g.current_user, relationship_id)
    confirmed = compute_balance(rel, include_pending=False)
    projected = compute_balance(rel, include_pending=True)
    return {
        "relationship_id": rel.id,
        "confirmed": serialize_balance_view(confirmed),
        "projected": serialize_balance_view(projected),
    }, 200
