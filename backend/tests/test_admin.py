"""System-administration surface: create-admin CLI, admin API, ledger reset."""

from __future__ import annotations

import pytest
from flask import Flask
from flask.testing import FlaskClient
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from app.extensions import db
from app.models import AuditLog, Comment, Expense, ExpenseShare, Payment, Relationship, User
from app.services.admin import RESET_CONFIRM_PHRASE
from tests.factories import (
    auth_headers,
    login,
    make_expense,
    make_logged_in_user,
    make_payment,
    make_relationship,
    make_user,
)


def create_admin_via_cli(app: Flask, username: str = "root") -> str:
    """Run ``flask create-admin`` and return the printed access key."""
    result = app.test_cli_runner().invoke(args=["create-admin", username])
    assert result.exit_code == 0, result.output
    # The key is the last non-empty output line.
    return [line for line in result.output.splitlines() if line.strip()][-1]


def seed_ledger(client: FlaskClient) -> dict:
    """Two users, an accepted relationship, one confirmed expense with a
    comment, one pending payment. Returns ids for later assertions."""
    alice, alice_token = make_logged_in_user(client, "alice")
    bob = make_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob")
    bob_token = login(client, "bob")
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
    comment_resp = client.post(
        f"/api/v1/expenses/{expense['id']}/comments",
        json={"content": "hello"},
        headers=auth_headers(alice_token),
    )
    assert comment_resp.status_code == 201, comment_resp.get_json()
    payment = make_payment(
        client,
        alice_token,
        relationship_id=rel["id"],
        from_user_id=alice["id"],
        to_user_id=bob["id"],
        amount_cents=250,
    )
    return {
        "alice_token": alice_token,
        "relationship_id": rel["id"],
        "expense_id": expense["id"],
        "payment_id": payment["id"],
    }


# --- create-admin CLI -------------------------------------------------


def test_create_admin_prints_key_and_key_logs_in(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)

    user = db.session.execute(db.select(User).where(User.username == "root")).scalar_one()
    assert user.is_admin is True

    resp = client.post("/api/v1/auth/login", json={"username": "root", "password": key})
    assert resp.status_code == 200
    assert resp.get_json()["user"]["username"] == "root"


def test_create_admin_refuses_existing_username(app: Flask, client: FlaskClient):
    make_user(client, "taken")
    result = app.test_cli_runner().invoke(args=["create-admin", "taken"])
    assert result.exit_code != 0
    assert "already exists" in result.output


def test_rotate_key_replaces_key_and_revokes_sessions(app: Flask, client: FlaskClient):
    old_key = create_admin_via_cli(app)
    old_token = login(client, "root", old_key)

    result = app.test_cli_runner().invoke(args=["create-admin", "root", "--rotate-key"])
    assert result.exit_code == 0, result.output
    new_key = [line for line in result.output.splitlines() if line.strip()][-1]
    assert new_key != old_key

    # Old key no longer logs in; old session is revoked; new key works.
    resp = client.post("/api/v1/auth/login", json={"username": "root", "password": old_key})
    assert resp.status_code == 401
    resp = client.get("/api/v1/auth/me", headers=auth_headers(old_token))
    assert resp.status_code == 401
    assert login(client, "root", new_key)


# --- is_admin exposure ------------------------------------------------


def test_me_reports_admin_flag(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)
    admin_token = login(client, "root", key)
    resp = client.get("/api/v1/auth/me", headers=auth_headers(admin_token))
    assert resp.status_code == 200
    assert resp.get_json()["is_admin"] is True

    _, user_token = make_logged_in_user(client, "alice")
    resp = client.get("/api/v1/auth/me", headers=auth_headers(user_token))
    assert resp.get_json()["is_admin"] is False


def test_admin_flag_not_leaked_to_counterparty(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)
    _, alice_token = make_logged_in_user(client, "alice")
    make_relationship(client, alice_token, "root", accept=False, invitee_password=key)
    resp = client.get("/api/v1/relationships", headers=auth_headers(alice_token))
    parties = resp.get_json()["items"][0]
    assert "is_admin" not in parties["invited_user"]
    assert "is_admin" not in parties["inviting_user"]


# --- authorization ----------------------------------------------------


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("get", "/api/v1/admin/stats"),
        ("post", "/api/v1/admin/cleanup-tokens"),
        ("post", "/api/v1/admin/reset-ledger"),
    ],
)
def test_admin_endpoints_require_auth(client: FlaskClient, method: str, path: str):
    resp = getattr(client, method)(path)
    assert resp.status_code == 401


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("get", "/api/v1/admin/stats"),
        ("post", "/api/v1/admin/cleanup-tokens"),
        ("post", "/api/v1/admin/reset-ledger"),
    ],
)
def test_admin_endpoints_hidden_from_normal_users(client: FlaskClient, method: str, path: str):
    _, token = make_logged_in_user(client, "alice")
    resp = getattr(client, method)(path, headers=auth_headers(token))
    assert resp.status_code == 404
    assert resp.get_json()["error"]["code"] == "not_found"


# --- stats ------------------------------------------------------------


def test_stats_counts_rows(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)
    seed_ledger(client)
    admin_token = login(client, "root", key)

    resp = client.get("/api/v1/admin/stats", headers=auth_headers(admin_token))
    assert resp.status_code == 200
    stats = resp.get_json()
    assert stats["users"] == 3  # root, alice, bob
    assert stats["relationships"] == 1
    assert stats["expenses"] == 1
    assert stats["payments"] == 1
    assert stats["comments"] == 1
    assert stats["active_tokens"] >= 1


# --- reset-ledger endpoint --------------------------------------------


def test_reset_requires_exact_confirmation(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)
    admin_token = login(client, "root", key)

    for body in (None, {}, {"confirm": "reset ledger"}, {"confirm": "yes"}):
        resp = client.post(
            "/api/v1/admin/reset-ledger",
            json=body,
            headers=auth_headers(admin_token),
        )
        assert resp.status_code == 422
        assert resp.get_json()["error"]["code"] == "confirmation_required"


def test_reset_erases_ledger_keeps_users_and_relationships(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)
    seed = seed_ledger(client)
    admin_token = login(client, "root", key)

    resp = client.post(
        "/api/v1/admin/reset-ledger",
        json={"confirm": RESET_CONFIRM_PHRASE},
        headers=auth_headers(admin_token),
    )
    assert resp.status_code == 200
    deleted = resp.get_json()["deleted"]
    assert deleted == {
        "expenses": 1,
        "expense_shares": 2,
        "payments": 1,
        "comments": 1,
    }

    assert db.session.scalar(db.select(db.func.count()).select_from(Expense)) == 0
    assert db.session.scalar(db.select(db.func.count()).select_from(ExpenseShare)) == 0
    assert db.session.scalar(db.select(db.func.count()).select_from(Payment)) == 0
    assert db.session.scalar(db.select(db.func.count()).select_from(Comment)) == 0
    # Users and relationships survive.
    assert db.session.scalar(db.select(db.func.count()).select_from(User)) == 3
    assert db.session.scalar(db.select(db.func.count()).select_from(Relationship)) == 1

    # The reset itself is audited.
    audit = db.session.execute(db.select(AuditLog).where(AuditLog.action == "reset")).scalar_one()
    assert audit.target_type == "ledger"

    # The relationship balance is settled again.
    bal = client.get(
        f"/api/v1/relationships/{seed['relationship_id']}/balance",
        headers=auth_headers(seed["alice_token"]),
    )
    assert bal.status_code == 200
    assert bal.get_json()["confirmed"]["net_cents"] == 0


def test_reset_reinstalls_delete_guard_triggers(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)
    seed = seed_ledger(client)
    admin_token = login(client, "root", key)

    resp = client.post(
        "/api/v1/admin/reset-ledger",
        json={"confirm": RESET_CONFIRM_PHRASE},
        headers=auth_headers(admin_token),
    )
    assert resp.status_code == 200

    # All eleven triggers are back in place.
    trigger_count = db.session.scalar(
        text("SELECT COUNT(*) FROM sqlite_schema WHERE type = 'trigger'")
    )
    assert trigger_count == 11

    # New entries can be created after the reset...
    alice = db.session.execute(db.select(User).where(User.username == "alice")).scalar_one()
    bob = db.session.execute(db.select(User).where(User.username == "bob")).scalar_one()
    make_expense(
        client,
        seed["alice_token"],
        relationship_id=seed["relationship_id"],
        payer_user_id=alice.id,
        total_cents=500,
        shares=[
            {"user_id": alice.id, "amount_cents": 250},
            {"user_id": bob.id, "amount_cents": 250},
        ],
    )
    make_payment(
        client,
        seed["alice_token"],
        relationship_id=seed["relationship_id"],
        from_user_id=alice.id,
        to_user_id=bob.id,
        amount_cents=100,
    )

    # ...and raw deletes are forbidden again: the guards were reinstalled.
    # (BEFORE DELETE triggers are per-row, so the tables must be
    # non-empty for this assertion to mean anything.)
    with pytest.raises(IntegrityError, match="expense_immutable"):
        db.session.execute(text("DELETE FROM expense"))
    db.session.rollback()
    with pytest.raises(IntegrityError, match="payment_immutable"):
        db.session.execute(text("DELETE FROM payment"))
    db.session.rollback()


# --- cleanup-tokens endpoint -------------------------------------------


def test_cleanup_tokens_endpoint(app: Flask, client: FlaskClient):
    key = create_admin_via_cli(app)
    # A revoked token to sweep: log in and log out as a normal user.
    make_logged_in_user(client, "alice")
    alice_token = login(client, "alice")
    resp = client.post("/api/v1/auth/logout", headers=auth_headers(alice_token))
    assert resp.status_code == 204

    admin_token = login(client, "root", key)
    resp = client.post("/api/v1/admin/cleanup-tokens", headers=auth_headers(admin_token))
    assert resp.status_code == 200
    assert resp.get_json()["deleted_tokens"] >= 1


# --- reset-ledger CLI ---------------------------------------------------


def test_reset_ledger_cli_requires_flag(app: Flask, client: FlaskClient):
    seed_ledger(client)
    result = app.test_cli_runner().invoke(args=["reset-ledger"])
    assert result.exit_code != 0
    assert "--yes-i-mean-it" in result.output
    assert db.session.scalar(db.select(db.func.count()).select_from(Expense)) == 1


def test_reset_ledger_cli_with_flag(app: Flask, client: FlaskClient):
    seed_ledger(client)
    result = app.test_cli_runner().invoke(args=["reset-ledger", "--yes-i-mean-it"])
    assert result.exit_code == 0, result.output
    assert "Ledger reset" in result.output
    assert db.session.scalar(db.select(db.func.count()).select_from(Expense)) == 0
    assert db.session.scalar(db.select(db.func.count()).select_from(Payment)) == 0
