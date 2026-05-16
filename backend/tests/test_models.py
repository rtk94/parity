"""Model and DB constraint tests."""

from __future__ import annotations

import pytest
from flask import Flask
from sqlalchemy.exc import IntegrityError

from app.auth.security import hash_password
from app.extensions import db
from app.models import (
    Expense,
    ExpenseStatus,
    Payment,
    PaymentStatus,
    Relationship,
    User,
)


def _make_user(username: str, display_name: str = "U") -> User:
    user = User(
        username=username,
        password_hash=hash_password("pw-" + username),
        display_name=display_name,
    )
    db.session.add(user)
    db.session.commit()
    return user


def test_user_can_be_created(app: Flask) -> None:
    user = _make_user("alice", "Alice")
    assert user.id is not None
    assert user.created_at is not None
    assert user.username == "alice"


def test_relationship_check_rejects_wrong_order(app: Flask) -> None:
    a = _make_user("alice")
    b = _make_user("bob")
    rel = Relationship(user_a_id=max(a.id, b.id), user_b_id=min(a.id, b.id))
    db.session.add(rel)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()


def test_relationship_check_rejects_self_loop(app: Flask) -> None:
    a = _make_user("alice")
    rel = Relationship(user_a_id=a.id, user_b_id=a.id)
    db.session.add(rel)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()


def _make_relationship(a: User, b: User) -> Relationship:
    lo, hi = sorted([a.id, b.id])
    rel = Relationship(user_a_id=lo, user_b_id=hi)
    db.session.add(rel)
    db.session.commit()
    return rel


def test_expense_check_rejects_self_confirmation(app: Flask) -> None:
    a = _make_user("alice")
    b = _make_user("bob")
    rel = _make_relationship(a, b)

    expense = Expense(
        payer_user_id=a.id,
        relationship_id=rel.id,
        total_cents=1000,
        description="lunch",
        created_by_user_id=a.id,
        status=ExpenseStatus.confirmed,
        confirmed_by_user_id=a.id,
    )
    db.session.add(expense)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()


def test_payment_check_rejects_same_parties(app: Flask) -> None:
    a = _make_user("alice")
    b = _make_user("bob")
    rel = _make_relationship(a, b)

    payment = Payment(
        from_user_id=a.id,
        to_user_id=a.id,
        relationship_id=rel.id,
        amount_cents=500,
        created_by_user_id=a.id,
    )
    db.session.add(payment)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()


def test_payment_check_rejects_self_confirmation(app: Flask) -> None:
    a = _make_user("alice")
    b = _make_user("bob")
    rel = _make_relationship(a, b)

    payment = Payment(
        from_user_id=a.id,
        to_user_id=b.id,
        relationship_id=rel.id,
        amount_cents=500,
        created_by_user_id=a.id,
        status=PaymentStatus.confirmed,
        confirmed_by_user_id=a.id,
    )
    db.session.add(payment)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()
