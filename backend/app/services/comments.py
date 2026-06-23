"""Comment service: create and list comments for expenses and payments."""

from __future__ import annotations

from typing import Any

from sqlalchemy import select

from app.extensions import db
from app.models import Comment, User
from app.services import BadRequestError, ValidationError
from app.services import expenses as expenses_service
from app.services import payments as payments_service


def _extract_content(payload: dict[str, Any] | None) -> str:
    if not isinstance(payload, dict):
        raise BadRequestError(message="JSON body required.")

    content = payload.get("content")
    if not isinstance(content, str) or not content.strip():
        raise ValidationError("empty_content", "Comment content is required.")

    content = content.strip()
    if len(content) > 512:
        raise ValidationError("content_too_long", "Comment content must be 512 characters or less.")

    return content


def create_for_expense(user: User, expense_id: int, payload: dict[str, Any] | None) -> Comment:
    # Ensure user has access to this expense
    expense = expenses_service.get_for_user(user, expense_id)
    content = _extract_content(payload)

    comment = Comment(
        user_id=user.id,
        expense_id=expense.id,
        content=content,
    )
    db.session.add(comment)
    db.session.commit()
    db.session.refresh(comment)
    return comment


def list_for_expense(user: User, expense_id: int) -> list[Comment]:
    # Ensure user has access
    expense = expenses_service.get_for_user(user, expense_id)
    stmt = (
        select(Comment)
        .where(Comment.expense_id == expense.id)
        .order_by(Comment.created_at.asc(), Comment.id.asc())
    )
    return list(db.session.execute(stmt).scalars().all())


def create_for_payment(user: User, payment_id: int, payload: dict[str, Any] | None) -> Comment:
    # Ensure user has access
    payment = payments_service.get_for_user(user, payment_id)
    content = _extract_content(payload)

    comment = Comment(
        user_id=user.id,
        payment_id=payment.id,
        content=content,
    )
    db.session.add(comment)
    db.session.commit()
    db.session.refresh(comment)
    return comment


def list_for_payment(user: User, payment_id: int) -> list[Comment]:
    # Ensure user has access
    payment = payments_service.get_for_user(user, payment_id)
    stmt = (
        select(Comment)
        .where(Comment.payment_id == payment.id)
        .order_by(Comment.created_at.asc(), Comment.id.asc())
    )
    return list(db.session.execute(stmt).scalars().all())
