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
    RelationshipStatus,
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


def _make_relationship(
    inviting: User,
    invited: User,
    *,
    status: RelationshipStatus = RelationshipStatus.accepted,
) -> Relationship:
    rel = Relationship(
        inviting_user_id=inviting.id,
        invited_user_id=invited.id,
        status=status,
    )
    db.session.add(rel)
    db.session.commit()
    return rel


def test_user_can_be_created(app: Flask) -> None:
    user = _make_user("alice", "Alice")
    assert user.id is not None
    assert user.created_at is not None
    assert user.username == "alice"


def test_relationship_check_rejects_self_invite(app: Flask) -> None:
    a = _make_user("alice")
    rel = Relationship(inviting_user_id=a.id, invited_user_id=a.id)
    db.session.add(rel)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()


def test_relationship_unique_blocks_same_direction_duplicate(app: Flask) -> None:
    a = _make_user("alice")
    b = _make_user("bob")
    _make_relationship(a, b, status=RelationshipStatus.pending)

    dup = Relationship(
        inviting_user_id=a.id,
        invited_user_id=b.id,
        status=RelationshipStatus.pending,
    )
    db.session.add(dup)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()


def test_relationship_partial_index_blocks_inverse_direction_duplicate(
    app: Flask,
) -> None:
    a = _make_user("alice")
    b = _make_user("bob")
    _make_relationship(a, b, status=RelationshipStatus.pending)

    inverse = Relationship(
        inviting_user_id=b.id,
        invited_user_id=a.id,
        status=RelationshipStatus.pending,
    )
    db.session.add(inverse)
    with pytest.raises(IntegrityError):
        db.session.commit()
    db.session.rollback()


def test_relationship_partial_index_allows_reinvite_after_rejection(
    app: Flask,
) -> None:
    a = _make_user("alice")
    b = _make_user("bob")
    _make_relationship(a, b, status=RelationshipStatus.rejected)

    # Same-direction re-invite after rejection works.
    reinvite = Relationship(
        inviting_user_id=a.id,
        invited_user_id=b.id,
        status=RelationshipStatus.pending,
    )
    db.session.add(reinvite)
    db.session.commit()
    assert reinvite.id is not None

    # Reject the new one too; then verify inverse-direction also works.
    reinvite.status = RelationshipStatus.rejected
    db.session.commit()

    inverse = Relationship(
        inviting_user_id=b.id,
        invited_user_id=a.id,
        status=RelationshipStatus.pending,
    )
    db.session.add(inverse)
    db.session.commit()
    assert inverse.id is not None


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
