"""Balance computation for a relationship.

The internal sign convention is: positive ``net_cents`` means the
party with the smaller user id is owed money by the party with the
larger user id. This is purely an implementation detail — the inviter
vs. invited distinction does not affect balance direction. The
response shape ``{from_user_id, to_user_id, net_cents}`` is derived
from the signed internal value (``from`` owes ``to``).
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select

from app.extensions import db
from app.models import (
    Expense,
    ExpenseShare,
    ExpenseStatus,
    Payment,
    PaymentStatus,
    Relationship,
    RelationshipStatus,
)
from app.services import ConflictError


@dataclass(frozen=True)
class BalanceView:
    net_cents: int
    from_user_id: int | None
    to_user_id: int | None


def compute_balance(relationship: Relationship, *, include_pending: bool) -> BalanceView:
    if relationship.status != RelationshipStatus.accepted:
        raise ConflictError("relationship_not_accepted", "Relationship is not accepted.")

    party_lo, party_hi = sorted([relationship.inviting_user_id, relationship.invited_user_id])

    expense_statuses = [ExpenseStatus.confirmed]
    payment_statuses = [PaymentStatus.confirmed]
    if include_pending:
        expense_statuses.append(ExpenseStatus.pending)
        payment_statuses.append(PaymentStatus.pending)

    net = 0  # positive means party_hi owes party_lo

    expenses = (
        db.session.execute(
            select(Expense).where(
                Expense.relationship_id == relationship.id,
                Expense.status.in_(expense_statuses),
            )
        )
        .scalars()
        .all()
    )

    for expense in expenses:
        sign = -1 if expense.reverses_expense_id is not None else 1
        shares = (
            db.session.execute(select(ExpenseShare).where(ExpenseShare.expense_id == expense.id))
            .scalars()
            .all()
        )
        for share in shares:
            if share.user_id == expense.payer_user_id:
                continue
            # share.user_id owes expense.payer_user_id by share.amount_cents.
            if share.user_id == party_hi:
                net += sign * share.amount_cents
            else:
                net -= sign * share.amount_cents

    payments = (
        db.session.execute(
            select(Payment).where(
                Payment.relationship_id == relationship.id,
                Payment.status.in_(payment_statuses),
            )
        )
        .scalars()
        .all()
    )

    for payment in payments:
        sign = -1 if payment.reverses_payment_id is not None else 1
        # A payment from F to T reduces F's debt to T by amount_cents.
        if payment.from_user_id == party_hi:
            # hi paid lo: hi's debt to lo decreases.
            net -= sign * payment.amount_cents
        else:
            # lo paid hi: hi's debt to lo increases (lo's debt to hi decreases).
            net += sign * payment.amount_cents

    if net > 0:
        return BalanceView(net_cents=net, from_user_id=party_hi, to_user_id=party_lo)
    if net < 0:
        return BalanceView(net_cents=-net, from_user_id=party_lo, to_user_id=party_hi)
    return BalanceView(net_cents=0, from_user_id=None, to_user_id=None)
