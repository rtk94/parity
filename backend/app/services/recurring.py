"""Recurring-expense service: manage templates and materialise due entries.

A recurring expense is a template that generates a pending expense each
time its schedule comes due. Templates are ordinary mutable
configuration; the expenses they produce go through the normal
immutable two-party ledger flow. Generation is driven by
``run_due`` (wired to the ``flask run-recurring`` CLI, intended to run
from a daily cron): a template fires at most once per call, and only
when ``next_run_on`` has arrived, so repeated runs on the same day are
idempotent and a dormant template catches up one period per run rather
than flooding the ledger.
"""

from __future__ import annotations

import calendar
from datetime import UTC, date, datetime
from typing import Any

from sqlalchemy import func, or_, select

from app.extensions import db
from app.models import (
    RecurringExpense,
    RecurringExpenseShare,
    RecurringInterval,
    Relationship,
    RelationshipStatus,
    User,
)
from app.services import (
    BadRequestError,
    ConflictError,
    NotFoundError,
    ValidationError,
    notifications,
)
from app.services import expenses as expenses_service
from app.services.audit import log_action

# Guard against a single dormant template generating an unbounded number
# of entries in one run. In normal daily-cron operation a template
# advances at most one period ahead of the run date, so this ceiling is
# never approached; it only bounds pathological catch-up.
_MAX_CATCH_UP = 1


def _advance(day: date, interval: RecurringInterval) -> date:
    """Return the next scheduled date after ``day`` for ``interval``.

    Monthly advances clamp to the last valid day of the target month so
    a template anchored on the 31st still fires in short months.
    """
    if interval is RecurringInterval.daily:
        return date.fromordinal(day.toordinal() + 1)
    if interval is RecurringInterval.weekly:
        return date.fromordinal(day.toordinal() + 7)
    # monthly
    month = day.month + 1
    year = day.year + (month - 1) // 12
    month = (month - 1) % 12 + 1
    last_day = calendar.monthrange(year, month)[1]
    return date(year, month, min(day.day, last_day))


def _require_relationship(user: User, relationship_id: Any) -> Relationship:
    if not isinstance(relationship_id, int) or isinstance(relationship_id, bool):
        raise NotFoundError("relationship_not_found", "Relationship not found.")
    rel = db.session.get(Relationship, relationship_id)
    if rel is None or user.id not in rel.parties():
        raise NotFoundError("relationship_not_found", "Relationship not found.")
    if rel.status != RelationshipStatus.accepted:
        raise ConflictError("relationship_not_accepted", "Relationship is not accepted.")
    return rel


def _validate_interval(value: Any) -> RecurringInterval:
    valid = {i.value for i in RecurringInterval}
    if not isinstance(value, str) or value not in valid:
        raise ValidationError(
            "invalid_interval",
            f"interval must be one of {sorted(valid)}.",
        )
    return RecurringInterval(value)


def _validate_start_date(value: Any) -> date:
    if not isinstance(value, str):
        raise ValidationError("invalid_start_date", "start_on must be an ISO date string.")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise ValidationError(
            "invalid_start_date", "start_on must be an ISO date (YYYY-MM-DD)."
        ) from exc


def _validate_category(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or len(value.strip()) > 64:
        raise ValidationError("invalid_category", "category must be a string under 64 characters.")
    return value.strip() or None


def _validate_money(
    rel: Relationship, payer_id: Any, total_cents: Any, shares: Any
) -> tuple[int, int, list[tuple[int, int]]]:
    """Validate payer, total, and shares against the relationship parties.

    Mirrors the expense-creation checks: positive integer cents, a payer
    that is a party, distinct share users that are all parties, and a
    share sum equal to ``total_cents``.
    """
    if not isinstance(total_cents, int) or isinstance(total_cents, bool) or total_cents <= 0:
        raise ValidationError("invalid_amount", "total_cents must be a positive integer.")

    parties = rel.parties()
    if not isinstance(payer_id, int) or isinstance(payer_id, bool) or payer_id not in parties:
        raise ValidationError("invalid_payer", "payer_user_id is not a party to the relationship.")

    if not isinstance(shares, list) or len(shares) == 0:
        raise ValidationError("empty_shares", "shares must be a non-empty list.")

    seen: set[int] = set()
    shares_sum = 0
    cleaned: list[tuple[int, int]] = []
    for share in shares:
        if not isinstance(share, dict):
            raise BadRequestError(message="Each share must be an object.")
        user_id = share.get("user_id")
        amount = share.get("amount_cents")
        if not isinstance(amount, int) or isinstance(amount, bool) or amount <= 0:
            raise ValidationError(
                "invalid_amount", "share amount_cents must be a positive integer."
            )
        if not isinstance(user_id, int) or isinstance(user_id, bool) or user_id not in parties:
            raise ValidationError(
                "invalid_share_user", "share user_id is not a party to the relationship."
            )
        if user_id in seen:
            raise ValidationError(
                "duplicate_share_user", f"user {user_id} appears in shares more than once."
            )
        seen.add(user_id)
        shares_sum += amount
        cleaned.append((user_id, amount))

    if shares_sum != total_cents:
        raise ValidationError(
            "share_sum_mismatch",
            "Sum of share amounts does not equal total_cents.",
            details={"total_cents": total_cents, "shares_sum_cents": shares_sum},
        )

    return payer_id, total_cents, cleaned


def create(creator: User, payload: dict[str, Any] | None) -> RecurringExpense:
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    rel = _require_relationship(creator, payload.get("relationship_id"))

    description = payload.get("description")
    if not isinstance(description, str) or not description.strip():
        raise ValidationError("empty_description", "description is required.")

    category = _validate_category(payload.get("category"))
    interval = _validate_interval(payload.get("interval"))

    start_raw = payload.get("start_on")
    next_run_on = (
        _validate_start_date(start_raw) if start_raw is not None else datetime.now(UTC).date()
    )

    payer_id, total_cents, cleaned_shares = _validate_money(
        rel, payload.get("payer_user_id"), payload.get("total_cents"), payload.get("shares")
    )

    template = RecurringExpense(
        relationship_id=rel.id,
        payer_user_id=payer_id,
        total_cents=total_cents,
        description=description.strip(),
        category=category,
        interval=interval,
        next_run_on=next_run_on,
        active=True,
        created_by_user_id=creator.id,
    )
    db.session.add(template)
    db.session.flush()

    for user_id, amount in cleaned_shares:
        db.session.add(
            RecurringExpenseShare(
                recurring_expense_id=template.id,
                user_id=user_id,
                amount_cents=amount,
            )
        )

    log_action(creator.id, "create", "recurring_expense", template.id)
    db.session.commit()
    db.session.refresh(template)
    return template


def list_for_user(
    user: User,
    *,
    relationship_id: int | None,
    active: bool | None,
    limit: int,
    offset: int,
) -> tuple[list[RecurringExpense], int]:
    visible_rel_ids = select(Relationship.id).where(
        or_(
            Relationship.inviting_user_id == user.id,
            Relationship.invited_user_id == user.id,
        )
    )
    stmt = select(RecurringExpense).where(RecurringExpense.relationship_id.in_(visible_rel_ids))
    if relationship_id is not None:
        stmt = stmt.where(RecurringExpense.relationship_id == relationship_id)
    if active is not None:
        stmt = stmt.where(RecurringExpense.active.is_(active))

    total = db.session.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()
    stmt = (
        stmt.order_by(RecurringExpense.next_run_on.asc(), RecurringExpense.id.asc())
        .limit(limit)
        .offset(offset)
    )
    items = list(db.session.execute(stmt).scalars().all())
    return items, total


def get_for_user(user: User, recurring_id: int) -> RecurringExpense:
    template = db.session.get(RecurringExpense, recurring_id)
    if template is None:
        raise NotFoundError("not_found", "Not found.")
    rel = db.session.get(Relationship, template.relationship_id)
    if rel is None or user.id not in rel.parties():
        raise NotFoundError("not_found", "Not found.")
    return template


def update(user: User, recurring_id: int, payload: dict[str, Any] | None) -> RecurringExpense:
    """Partially update a template. Only supplied fields change.

    ``total_cents`` and ``shares`` must be supplied together (they are
    validated as a consistent pair); ``payer_user_id`` may accompany
    them or default to the current payer.
    """
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    template = get_for_user(user, recurring_id)
    rel = db.session.get(Relationship, template.relationship_id)

    if "active" in payload:
        active = payload.get("active")
        if not isinstance(active, bool):
            raise ValidationError("invalid_active", "active must be a boolean.")
        template.active = active

    if "description" in payload:
        description = payload.get("description")
        if not isinstance(description, str) or not description.strip():
            raise ValidationError("empty_description", "description is required.")
        template.description = description.strip()

    if "category" in payload:
        template.category = _validate_category(payload.get("category"))

    if "interval" in payload:
        template.interval = _validate_interval(payload.get("interval"))

    if "next_run_on" in payload:
        template.next_run_on = _validate_start_date(payload.get("next_run_on"))

    has_total = "total_cents" in payload
    has_shares = "shares" in payload
    if has_total or has_shares:
        if not (has_total and has_shares):
            raise ValidationError(
                "money_fields_paired",
                "total_cents and shares must be updated together.",
            )
        payer_id = payload.get("payer_user_id", template.payer_user_id)
        payer_id, total_cents, cleaned_shares = _validate_money(
            rel, payer_id, payload.get("total_cents"), payload.get("shares")
        )
        template.payer_user_id = payer_id
        template.total_cents = total_cents
        template.shares.clear()
        db.session.flush()
        for share_user_id, amount in cleaned_shares:
            template.shares.append(
                RecurringExpenseShare(user_id=share_user_id, amount_cents=amount)
            )
    elif "payer_user_id" in payload:
        raise ValidationError(
            "money_fields_paired",
            "payer_user_id can only change alongside total_cents and shares.",
        )

    log_action(user.id, "update", "recurring_expense", template.id)
    db.session.commit()
    db.session.refresh(template)
    return template


def delete(user: User, recurring_id: int) -> None:
    template = get_for_user(user, recurring_id)
    log_action(user.id, "delete", "recurring_expense", template.id)
    db.session.delete(template)
    db.session.commit()


def run_due(as_of: date | None = None) -> int:
    """Materialise pending expenses for every template due on or before ``as_of``.

    Returns the number of expenses generated. Only active templates on
    accepted relationships fire, and each fires at most ``_MAX_CATCH_UP``
    time(s) per call (once, in practice), advancing ``next_run_on`` by
    one interval each time.
    """
    if as_of is None:
        as_of = datetime.now(UTC).date()

    accepted_rel_ids = select(Relationship.id).where(
        Relationship.status == RelationshipStatus.accepted
    )
    stmt = select(RecurringExpense).where(
        RecurringExpense.active.is_(True),
        RecurringExpense.next_run_on <= as_of,
        RecurringExpense.relationship_id.in_(accepted_rel_ids),
    )
    templates = list(db.session.execute(stmt).scalars().all())

    generated: list[Any] = []
    for template in templates:
        rel = db.session.get(Relationship, template.relationship_id)
        creator = db.session.get(User, template.created_by_user_id)
        if rel is None or creator is None:
            continue
        expense_payload = {
            "payer_user_id": template.payer_user_id,
            "total_cents": template.total_cents,
            "description": template.description,
            "category": template.category,
            "shares": [
                {"user_id": s.user_id, "amount_cents": s.amount_cents} for s in template.shares
            ],
        }
        fired = 0
        while template.next_run_on <= as_of and fired < _MAX_CATCH_UP:
            expense = expenses_service._stage_expense_against(creator, rel, expense_payload)
            generated.append(expense)
            template.next_run_on = _advance(template.next_run_on, template.interval)
            fired += 1
        if fired:
            template.last_run_at = datetime.now(UTC)

    for expense in generated:
        log_action(expense.created_by_user_id, "create", "expense", expense.id)

    db.session.commit()

    for expense in generated:
        notifications.notify_new_expense(expense)

    return len(generated)
