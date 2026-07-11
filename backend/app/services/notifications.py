"""Push notification dispatch (best-effort).

Catalogue:

- a new pending entry → the counterparty who must confirm it;
- a confirmation → the creator whose entry was accepted;
- a discard → the other party, whose pending entry is now gone;
- a reversal → the counterparty who must confirm the new reversing entry;
- a relationship invite → the invited user.

Every dispatch is best-effort: any failure — a dead push provider, a
missing row — is swallowed and logged, never propagated. These are
called *after* the write has committed, so a notification problem must
never surface as a failed expense/payment/invite.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from functools import wraps

from flask import current_app
from sqlalchemy import select

from app.extensions import db
from app.models import DeviceToken, Expense, Payment, Relationship, User

logger = logging.getLogger(__name__)


def _best_effort(fn: Callable[..., None]) -> Callable[..., None]:
    """Wrap a dispatch function so any failure is logged, never raised."""

    @wraps(fn)
    def wrapper(*args: object, **kwargs: object) -> None:
        try:
            fn(*args, **kwargs)
        except Exception:
            logger.exception("push notification failed: %s", fn.__name__)

    return wrapper


def _counterparty(rel: Relationship, actor_id: int) -> int:
    return next(uid for uid in rel.parties() if uid != actor_id)


def _format_amount(cents: int, currency_code: str) -> str:
    # Integer-only formatting (no floats): major.minor plus the code.
    return f"{cents // 100}.{cents % 100:02d} {currency_code}"


def _data(kind: str, state: str, entry_id: int, relationship_id: int) -> dict[str, str]:
    """Deep-link payload the client uses to open the right screen."""
    return {
        "type": f"{kind}_{state}",
        "entry_kind": kind,
        "entry_id": str(entry_id),
        "relationship_id": str(relationship_id),
    }


def _dispatch(recipient_id: int, title: str, body: str, data: dict[str, str]) -> None:
    sender = current_app.extensions.get("push_sender")
    if sender is None:
        return
    tokens = list(
        db.session.execute(
            select(DeviceToken.token).where(DeviceToken.user_id == recipient_id)
        ).scalars()
    )
    if not tokens:
        return
    from app.services.push_sender import PushMessage

    sender.send(PushMessage(tokens=tokens, title=title, body=body, data=data))


@_best_effort
def notify_new_expense(expense: Expense) -> None:
    rel = db.session.get(Relationship, expense.relationship_id)
    if rel is None:
        return
    creator = db.session.get(User, expense.created_by_user_id)
    amount = _format_amount(expense.total_cents, rel.currency_code)
    _dispatch(
        _counterparty(rel, expense.created_by_user_id),
        title="New expense",
        body=f"{creator.display_name} logged a {amount} expense.",
        data=_data("expense", "pending", expense.id, rel.id),
    )


@_best_effort
def notify_expense_confirmed(expense: Expense) -> None:
    rel = db.session.get(Relationship, expense.relationship_id)
    if rel is None:
        return
    confirmer = db.session.get(User, expense.confirmed_by_user_id)
    amount = _format_amount(expense.total_cents, rel.currency_code)
    _dispatch(
        expense.created_by_user_id,
        title="Expense confirmed",
        body=f"{confirmer.display_name} confirmed your {amount} expense.",
        data=_data("expense", "confirmed", expense.id, rel.id),
    )


@_best_effort
def notify_new_payment(payment: Payment) -> None:
    rel = db.session.get(Relationship, payment.relationship_id)
    if rel is None:
        return
    creator = db.session.get(User, payment.created_by_user_id)
    amount = _format_amount(payment.amount_cents, rel.currency_code)
    _dispatch(
        _counterparty(rel, payment.created_by_user_id),
        title="New payment",
        body=f"{creator.display_name} logged a {amount} payment.",
        data=_data("payment", "pending", payment.id, rel.id),
    )


@_best_effort
def notify_payment_confirmed(payment: Payment) -> None:
    rel = db.session.get(Relationship, payment.relationship_id)
    if rel is None:
        return
    confirmer = db.session.get(User, payment.confirmed_by_user_id)
    amount = _format_amount(payment.amount_cents, rel.currency_code)
    _dispatch(
        payment.created_by_user_id,
        title="Payment confirmed",
        body=f"{confirmer.display_name} confirmed your {amount} payment.",
        data=_data("payment", "confirmed", payment.id, rel.id),
    )


@_best_effort
def notify_expense_discarded(expense: Expense) -> None:
    rel = db.session.get(Relationship, expense.relationship_id)
    if rel is None:
        return
    actor = db.session.get(User, expense.discarded_by_user_id)
    amount = _format_amount(expense.total_cents, rel.currency_code)
    _dispatch(
        _counterparty(rel, expense.discarded_by_user_id),
        title="Expense discarded",
        body=f"{actor.display_name} discarded a {amount} expense.",
        data=_data("expense", "discarded", expense.id, rel.id),
    )


@_best_effort
def notify_payment_discarded(payment: Payment) -> None:
    rel = db.session.get(Relationship, payment.relationship_id)
    if rel is None:
        return
    actor = db.session.get(User, payment.discarded_by_user_id)
    amount = _format_amount(payment.amount_cents, rel.currency_code)
    _dispatch(
        _counterparty(rel, payment.discarded_by_user_id),
        title="Payment discarded",
        body=f"{actor.display_name} discarded a {amount} payment.",
        data=_data("payment", "discarded", payment.id, rel.id),
    )


@_best_effort
def notify_expense_reversed(reversal: Expense) -> None:
    rel = db.session.get(Relationship, reversal.relationship_id)
    if rel is None:
        return
    actor = db.session.get(User, reversal.created_by_user_id)
    amount = _format_amount(reversal.total_cents, rel.currency_code)
    _dispatch(
        _counterparty(rel, reversal.created_by_user_id),
        title="Expense reversed",
        body=f"{actor.display_name} reversed a {amount} expense.",
        data=_data("expense", "reversed", reversal.id, rel.id),
    )


@_best_effort
def notify_payment_reversed(reversal: Payment) -> None:
    rel = db.session.get(Relationship, reversal.relationship_id)
    if rel is None:
        return
    actor = db.session.get(User, reversal.created_by_user_id)
    amount = _format_amount(reversal.amount_cents, rel.currency_code)
    _dispatch(
        _counterparty(rel, reversal.created_by_user_id),
        title="Payment reversed",
        body=f"{actor.display_name} reversed a {amount} payment.",
        data=_data("payment", "reversed", reversal.id, rel.id),
    )


@_best_effort
def notify_relationship_invite(relationship: Relationship) -> None:
    inviter = db.session.get(User, relationship.inviting_user_id)
    _dispatch(
        relationship.invited_user_id,
        title="New invitation",
        body=f"{inviter.display_name} invited you to a shared ledger.",
        data={
            "type": "relationship_invite",
            "relationship_id": str(relationship.id),
        },
    )
