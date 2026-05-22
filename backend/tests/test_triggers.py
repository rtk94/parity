"""DB-level immutability trigger tests.

The triggers are a backstop — service code already enforces these
invariants — so these tests bypass services and write raw SQL via the
SQLAlchemy session to verify each trigger actually fires when it should
(and stays out of the way when it shouldn't).

Test setup uses the public HTTP API via the Phase 2 factories to plant
realistic rows (relationships, expenses, payments) into the schema,
then attempts the illegal mutation via ``db.session.execute(text(...))``
and asserts ``IntegrityError``. Legitimate confirm/discard transitions
are exercised the same way to confirm the trigger ``WHEN`` clauses do
not over-reject.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import pytest
from flask import Flask
from flask.testing import FlaskClient
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from app.extensions import db
from tests.factories import (
    auth_headers,
    make_expense,
    make_logged_in_user,
    make_payment,
    make_relationship,
)

# --- helpers ----------------------------------------------------------


def _iso_now() -> str:
    # SQLAlchemy stores DateTime(timezone=True) values as the ISO 8601
    # string SQLite knows how to round-trip; this matches the format
    # already in the DB for confirmed_at/discarded_at.
    return datetime.now(UTC).strftime("%Y-%m-%d %H:%M:%S.%f%z")


def _expect_integrity_error(stmt: str, **params: Any) -> str:
    """Run ``stmt`` and assert it raises ``IntegrityError``. Returns the message."""
    with pytest.raises(IntegrityError) as excinfo:
        db.session.execute(text(stmt), params)
        db.session.commit()
    db.session.rollback()
    return str(excinfo.value.orig)


def _two_party_setup(
    client: FlaskClient,
) -> tuple[dict[str, Any], dict[str, Any], str, str, dict[str, Any]]:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    return alice, bob, alice_token, bob_token, rel


# --- expense table ----------------------------------------------------


def test_delete_confirmed_expense_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
        confirm_token=bob_token,
    )

    msg = _expect_integrity_error("DELETE FROM expense WHERE id = :id", id=expense["id"])
    assert "expense_immutable" in msg


def test_delete_pending_expense_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )

    msg = _expect_integrity_error("DELETE FROM expense WHERE id = :id", id=expense["id"])
    assert "expense_immutable" in msg


def test_update_confirmed_expense_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
        confirm_token=bob_token,
    )

    msg = _expect_integrity_error(
        "UPDATE expense SET rejection_reason = 'no' WHERE id = :id",
        id=expense["id"],
    )
    assert "expense_immutable" in msg


def test_update_discarded_expense_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )
    discard_resp = client.post(
        f"/api/v1/expenses/{expense['id']}/discard",
        headers=auth_headers(alice_token),
    )
    assert discard_resp.status_code == 200

    msg = _expect_integrity_error(
        "UPDATE expense SET rejection_reason = 'changed' WHERE id = :id",
        id=expense["id"],
    )
    assert "expense_immutable" in msg


def test_update_pending_expense_changing_total_cents_raises(
    client: FlaskClient, app: Flask
) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )

    msg = _expect_integrity_error(
        "UPDATE expense SET total_cents = 9999 WHERE id = :id", id=expense["id"]
    )
    assert "expense_immutable" in msg


def test_update_pending_expense_changing_payer_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )

    msg = _expect_integrity_error(
        "UPDATE expense SET payer_user_id = :new WHERE id = :id",
        new=bob["id"],
        id=expense["id"],
    )
    assert "expense_immutable" in msg


def test_update_pending_expense_legit_confirm_succeeds(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )

    db.session.execute(
        text(
            "UPDATE expense "
            "SET status = 'confirmed', "
            "    confirmed_at = :ts, "
            "    confirmed_by_user_id = :uid "
            "WHERE id = :id"
        ),
        {"ts": _iso_now(), "uid": bob["id"], "id": expense["id"]},
    )
    db.session.commit()

    row = db.session.execute(
        text("SELECT status FROM expense WHERE id = :id"), {"id": expense["id"]}
    ).scalar_one()
    assert row == "confirmed"


def test_update_pending_expense_legit_discard_succeeds(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )

    db.session.execute(
        text(
            "UPDATE expense "
            "SET status = 'discarded', "
            "    discarded_at = :ts, "
            "    discarded_by_user_id = :uid, "
            "    rejection_reason = 'manual cleanup' "
            "WHERE id = :id"
        ),
        {"ts": _iso_now(), "uid": alice["id"], "id": expense["id"]},
    )
    db.session.commit()

    row = db.session.execute(
        text("SELECT status, rejection_reason FROM expense WHERE id = :id"),
        {"id": expense["id"]},
    ).one()
    assert row.status == "discarded"
    assert row.rejection_reason == "manual cleanup"


# --- expense_share table ---------------------------------------------


def test_update_expense_share_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )
    share_id = db.session.execute(
        text("SELECT id FROM expense_share WHERE expense_id = :id ORDER BY id LIMIT 1"),
        {"id": expense["id"]},
    ).scalar_one()

    msg = _expect_integrity_error(
        "UPDATE expense_share SET amount_cents = 1 WHERE id = :id", id=share_id
    )
    assert "expense_share_immutable" in msg


def test_delete_expense_share_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 500},
            {"user_id": bob["id"], "amount_cents": 500},
        ],
    )
    share_id = db.session.execute(
        text("SELECT id FROM expense_share WHERE expense_id = :id ORDER BY id LIMIT 1"),
        {"id": expense["id"]},
    ).scalar_one()

    msg = _expect_integrity_error("DELETE FROM expense_share WHERE id = :id", id=share_id)
    assert "expense_share_immutable" in msg


# --- payment table ----------------------------------------------------


def test_delete_pending_payment_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    msg = _expect_integrity_error("DELETE FROM payment WHERE id = :id", id=payment["id"])
    assert "payment_immutable" in msg


def test_delete_confirmed_payment_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )

    msg = _expect_integrity_error("DELETE FROM payment WHERE id = :id", id=payment["id"])
    assert "payment_immutable" in msg


def test_update_terminal_payment_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
        confirm_token=bob_token,
    )

    msg = _expect_integrity_error(
        "UPDATE payment SET description = 'changed' WHERE id = :id",
        id=payment["id"],
    )
    assert "payment_immutable" in msg


def test_update_pending_payment_changing_amount_raises(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    msg = _expect_integrity_error(
        "UPDATE payment SET amount_cents = 9999 WHERE id = :id", id=payment["id"]
    )
    assert "payment_immutable" in msg


def test_update_pending_payment_legit_confirm_succeeds(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    db.session.execute(
        text(
            "UPDATE payment "
            "SET status = 'confirmed', "
            "    confirmed_at = :ts, "
            "    confirmed_by_user_id = :uid "
            "WHERE id = :id"
        ),
        {"ts": _iso_now(), "uid": bob["id"], "id": payment["id"]},
    )
    db.session.commit()
    assert (
        db.session.execute(
            text("SELECT status FROM payment WHERE id = :id"), {"id": payment["id"]}
        ).scalar_one()
        == "confirmed"
    )


def test_update_pending_payment_legit_discard_succeeds(client: FlaskClient, app: Flask) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=1000,
    )

    db.session.execute(
        text(
            "UPDATE payment "
            "SET status = 'discarded', "
            "    discarded_at = :ts, "
            "    discarded_by_user_id = :uid, "
            "    rejection_reason = 'manual cleanup' "
            "WHERE id = :id"
        ),
        {"ts": _iso_now(), "uid": alice["id"], "id": payment["id"]},
    )
    db.session.commit()
    row = db.session.execute(
        text("SELECT status, rejection_reason FROM payment WHERE id = :id"),
        {"id": payment["id"]},
    ).one()
    assert row.status == "discarded"
    assert row.rejection_reason == "manual cleanup"


# --- relationship table ----------------------------------------------


def test_update_accepted_relationship_raises(client: FlaskClient, app: Flask) -> None:
    _, _, alice_token, _, rel = _two_party_setup(client)

    msg = _expect_integrity_error(
        "UPDATE relationship SET status = 'pending' WHERE id = :id",
        id=rel["id"],
    )
    assert "relationship_immutable" in msg


def test_update_rejected_relationship_raises(client: FlaskClient, app: Flask) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)
    reject_resp = client.post(
        f"/api/v1/relationships/{rel['id']}/reject",
        headers=auth_headers(bob_token),
    )
    assert reject_resp.status_code == 200

    msg = _expect_integrity_error(
        "UPDATE relationship SET status = 'pending' WHERE id = :id",
        id=rel["id"],
    )
    assert "relationship_immutable" in msg


def test_update_pending_relationship_changing_inviting_user_raises(
    client: FlaskClient, app: Flask
) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, bob_token = make_logged_in_user(client, "bob")  # noqa: F841
    rel = make_relationship(client, alice_token, "bob", accept=False)
    carol_id = db.session.execute(
        text("INSERT INTO user (username, password_hash, display_name) VALUES ('carol', 'h', 'C')")
    ).lastrowid
    db.session.commit()

    msg = _expect_integrity_error(
        "UPDATE relationship SET inviting_user_id = :new WHERE id = :id",
        new=carol_id,
        id=rel["id"],
    )
    assert "relationship_immutable" in msg


def test_update_pending_relationship_to_accepted_succeeds(client: FlaskClient, app: Flask) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, _ = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    db.session.execute(
        text("UPDATE relationship SET status = 'accepted' WHERE id = :id"),
        {"id": rel["id"]},
    )
    db.session.commit()
    assert (
        db.session.execute(
            text("SELECT status FROM relationship WHERE id = :id"), {"id": rel["id"]}
        ).scalar_one()
        == "accepted"
    )


def test_update_pending_relationship_to_rejected_succeeds(client: FlaskClient, app: Flask) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, _ = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)

    db.session.execute(
        text("UPDATE relationship SET status = 'rejected' WHERE id = :id"),
        {"id": rel["id"]},
    )
    db.session.commit()
    assert (
        db.session.execute(
            text("SELECT status FROM relationship WHERE id = :id"), {"id": rel["id"]}
        ).scalar_one()
        == "rejected"
    )


def test_delete_relationship_raises(client: FlaskClient, app: Flask) -> None:
    _, alice_token = make_logged_in_user(client, "alice")
    _, _ = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)

    msg = _expect_integrity_error("DELETE FROM relationship WHERE id = :id", id=rel["id"])
    assert "relationship_immutable" in msg
