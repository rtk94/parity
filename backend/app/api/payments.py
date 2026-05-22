"""Payment endpoints: create, list, get, confirm, discard, reverse."""

from __future__ import annotations

from flask import Blueprint, g, request

from app.api._helpers import int_query_arg, json_body, translates_service_errors
from app.api._serializers import serialize_payment
from app.auth.decorators import login_required
from app.services import payments as payments_service

payments_bp = Blueprint("payments", __name__, url_prefix="/api/v1/payments")


@payments_bp.post("")
@login_required
@translates_service_errors
def create_payment():
    payment = payments_service.create(g.current_user, json_body())
    return serialize_payment(payment), 201


@payments_bp.get("")
@login_required
@translates_service_errors
def list_payments():
    relationship_id = int_query_arg("relationship_id")
    status = request.args.get("status")
    items = payments_service.list_for_user(
        g.current_user,
        relationship_id=relationship_id,
        status=status,
    )
    return {"items": [serialize_payment(p) for p in items]}, 200


@payments_bp.get("/<int:payment_id>")
@login_required
@translates_service_errors
def get_payment(payment_id: int):
    payment = payments_service.get_for_user(g.current_user, payment_id)
    return serialize_payment(payment), 200


@payments_bp.post("/<int:payment_id>/confirm")
@login_required
@translates_service_errors
def confirm_payment(payment_id: int):
    payment = payments_service.confirm(g.current_user, payment_id)
    return serialize_payment(payment), 200


@payments_bp.post("/<int:payment_id>/discard")
@login_required
@translates_service_errors
def discard_payment(payment_id: int):
    payment = payments_service.discard(g.current_user, payment_id, json_body())
    return serialize_payment(payment), 200


@payments_bp.post("/<int:payment_id>/reverse")
@login_required
@translates_service_errors
def reverse_payment(payment_id: int):
    reversal = payments_service.reverse(g.current_user, payment_id)
    return serialize_payment(reversal), 201
