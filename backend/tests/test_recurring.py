"""Recurring-expense endpoint and generation tests."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from typing import Any

from flask import Flask
from flask.testing import FlaskClient

from app.models import RecurringInterval
from app.services import recurring as recurring_service
from tests.factories import (
    auth_headers,
    make_logged_in_user,
    make_recurring_expense,
    make_relationship,
)


def _two_party_setup(
    client: FlaskClient,
) -> tuple[dict[str, Any], dict[str, Any], str, str, dict[str, Any]]:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    return alice, bob, alice_token, bob_token, rel


def _even_shares(alice: dict, bob: dict, total: int) -> list[dict[str, int]]:
    half = total // 2
    return [
        {"user_id": alice["id"], "amount_cents": half},
        {"user_id": bob["id"], "amount_cents": total - half},
    ]


# --- create -----------------------------------------------------------------


def test_create_happy_path(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    today = datetime.now(UTC).date().isoformat()
    template = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=2000,
        shares=_even_shares(alice, bob, 2000),
        interval="monthly",
        description="Rent",
        category="housing",
        start_on=today,
    )
    assert template["interval"] == "monthly"
    assert template["next_run_on"] == today
    assert template["active"] is True
    assert template["category"] == "housing"
    assert template["last_run_at"] is None
    assert template["created_by_user_id"] == alice["id"]
    assert len(template["shares"]) == 2


def test_create_defaults_start_to_today(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    template = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
    )
    assert template["next_run_on"] == datetime.now(UTC).date().isoformat()


def test_create_invalid_interval(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    resp = client.post(
        "/api/v1/recurring",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Rent",
            "shares": _even_shares(alice, bob, 1000),
            "interval": "fortnightly",
        },
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_interval"


def test_create_invalid_start_date(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    resp = client.post(
        "/api/v1/recurring",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Rent",
            "shares": _even_shares(alice, bob, 1000),
            "interval": "weekly",
            "start_on": "not-a-date",
        },
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "invalid_start_date"


def test_create_share_sum_mismatch(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    resp = client.post(
        "/api/v1/recurring",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Rent",
            "shares": [
                {"user_id": alice["id"], "amount_cents": 400},
                {"user_id": bob["id"], "amount_cents": 400},
            ],
            "interval": "weekly",
        },
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "share_sum_mismatch"


def test_create_empty_description(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    resp = client.post(
        "/api/v1/recurring",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "   ",
            "shares": _even_shares(alice, bob, 1000),
            "interval": "weekly",
        },
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "empty_description"


def test_create_non_party_is_404(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    _, carol_token = make_logged_in_user(client, "carol")
    resp = client.post(
        "/api/v1/recurring",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Rent",
            "shares": _even_shares(alice, bob, 1000),
            "interval": "weekly",
        },
        headers=auth_headers(carol_token),
    )
    assert resp.status_code == 404
    assert resp.get_json()["error"]["code"] == "relationship_not_found"


def test_create_on_pending_relationship_conflicts(client: FlaskClient) -> None:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, _ = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=False)
    resp = client.post(
        "/api/v1/recurring",
        json={
            "relationship_id": rel["id"],
            "payer_user_id": alice["id"],
            "total_cents": 1000,
            "description": "Rent",
            "shares": _even_shares(alice, bob, 1000),
            "interval": "weekly",
        },
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 409
    assert resp.get_json()["error"]["code"] == "relationship_not_accepted"


# --- list / get -------------------------------------------------------------


def test_list_and_active_filter(client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    t1 = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
        description="Rent",
    )
    make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=500,
        shares=_even_shares(alice, bob, 500),
        description="Netflix",
    )
    # Pause the first
    client.patch(
        f"/api/v1/recurring/{t1['id']}",
        json={"active": False},
        headers=auth_headers(alice_token),
    )

    all_res = client.get(
        f"/api/v1/recurring?relationship_id={rel['id']}", headers=auth_headers(bob_token)
    )
    assert all_res.status_code == 200
    assert all_res.get_json()["total"] == 2

    active_res = client.get("/api/v1/recurring?active=true", headers=auth_headers(alice_token))
    active_items = active_res.get_json()["items"]
    assert len(active_items) == 1
    assert active_items[0]["description"] == "Netflix"


def test_get_non_party_is_404(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    _, carol_token = make_logged_in_user(client, "carol")
    template = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
    )
    resp = client.get(f"/api/v1/recurring/{template['id']}", headers=auth_headers(carol_token))
    assert resp.status_code == 404


# --- update / delete --------------------------------------------------------


def test_update_reschedule_and_edit_money(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    template = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
    )
    resp = client.patch(
        f"/api/v1/recurring/{template['id']}",
        json={
            "next_run_on": "2030-01-15",
            "total_cents": 3000,
            "payer_user_id": bob["id"],
            "shares": [
                {"user_id": alice["id"], "amount_cents": 1000},
                {"user_id": bob["id"], "amount_cents": 2000},
            ],
        },
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["next_run_on"] == "2030-01-15"
    assert body["total_cents"] == 3000
    assert body["payer_user_id"] == bob["id"]
    assert {s["amount_cents"] for s in body["shares"]} == {1000, 2000}


def test_update_money_fields_must_be_paired(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    template = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
    )
    resp = client.patch(
        f"/api/v1/recurring/{template['id']}",
        json={"total_cents": 3000},
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "money_fields_paired"


def test_delete_then_get_404(client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    template = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
    )
    del_res = client.delete(
        f"/api/v1/recurring/{template['id']}", headers=auth_headers(alice_token)
    )
    assert del_res.status_code == 204
    get_res = client.get(f"/api/v1/recurring/{template['id']}", headers=auth_headers(alice_token))
    assert get_res.status_code == 404


# --- generation (run_due) ---------------------------------------------------


def test_run_due_generates_pending_expense(app: Flask, client: FlaskClient) -> None:
    alice, bob, alice_token, bob_token, rel = _two_party_setup(client)
    today = datetime.now(UTC).date()
    template = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=2000,
        shares=_even_shares(alice, bob, 2000),
        interval="monthly",
        description="Rent",
        start_on=today.isoformat(),
    )

    generated = recurring_service.run_due(as_of=today)
    assert generated == 1

    # The counterparty (bob) sees a pending expense to confirm.
    pending = client.get(
        f"/api/v1/expenses?relationship_id={rel['id']}&status=pending",
        headers=auth_headers(bob_token),
    ).get_json()["items"]
    assert len(pending) == 1
    assert pending[0]["description"] == "Rent"
    assert pending[0]["total_cents"] == 2000
    assert pending[0]["payer_user_id"] == alice["id"]

    # The template advanced one month and recorded the run.
    refreshed = client.get(
        f"/api/v1/recurring/{template['id']}", headers=auth_headers(alice_token)
    ).get_json()
    expected_next = date(
        today.year + (today.month // 12), (today.month % 12) + 1, min(today.day, 28)
    )
    assert refreshed["next_run_on"] >= expected_next.isoformat()
    assert refreshed["last_run_at"] is not None


def test_run_due_is_idempotent_within_period(app: Flask, client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    today = datetime.now(UTC).date()
    make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
        interval="monthly",
        start_on=today.isoformat(),
    )
    assert recurring_service.run_due(as_of=today) == 1
    # Second run on the same day: the template already advanced past today.
    assert recurring_service.run_due(as_of=today) == 0


def test_run_due_skips_future_and_inactive(app: Flask, client: FlaskClient) -> None:
    alice, bob, alice_token, _, rel = _two_party_setup(client)
    today = datetime.now(UTC).date()
    tomorrow = today + timedelta(days=1)

    # Not yet due.
    make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=1000,
        shares=_even_shares(alice, bob, 1000),
        interval="weekly",
        start_on=tomorrow.isoformat(),
    )
    # Due but paused.
    paused = make_recurring_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=500,
        shares=_even_shares(alice, bob, 500),
        interval="weekly",
        start_on=today.isoformat(),
    )
    client.patch(
        f"/api/v1/recurring/{paused['id']}",
        json={"active": False},
        headers=auth_headers(alice_token),
    )

    assert recurring_service.run_due(as_of=today) == 0


def test_advance_monthly_clamps_to_short_month() -> None:
    # Jan 31 -> Feb 28 (2027 is not a leap year).
    assert recurring_service._advance(date(2027, 1, 31), RecurringInterval.monthly) == date(
        2027, 2, 28
    )
    # Dec -> Jan of next year.
    assert recurring_service._advance(date(2027, 12, 15), RecurringInterval.monthly) == date(
        2028, 1, 15
    )


def test_advance_weekly_and_daily() -> None:
    assert recurring_service._advance(date(2027, 3, 1), RecurringInterval.weekly) == date(
        2027, 3, 8
    )
    assert recurring_service._advance(date(2027, 3, 1), RecurringInterval.daily) == date(2027, 3, 2)
