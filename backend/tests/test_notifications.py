"""Push notification dispatch tests (with a fake sender)."""

from __future__ import annotations

from flask import Flask
from flask.testing import FlaskClient

from app.services.push_sender import PushMessage
from tests.factories import (
    auth_headers,
    make_expense,
    make_logged_in_user,
    make_payment,
    make_relationship,
)


class FakeSender:
    """Captures sent messages instead of hitting a push provider."""

    def __init__(self) -> None:
        self.messages: list[PushMessage] = []

    def send(self, message: PushMessage) -> None:
        self.messages.append(message)


class BoomSender:
    """Always fails, to prove dispatch is best-effort."""

    def send(self, message: PushMessage) -> None:
        raise RuntimeError("push provider is down")


def _install(app: Flask, sender: object) -> None:
    app.extensions["push_sender"] = sender


def _register_device(client: FlaskClient, token: str, device_token: str) -> None:
    resp = client.post(
        "/api/v1/auth/devices",
        json={"token": device_token},
        headers=auth_headers(token),
    )
    assert resp.status_code == 200, resp.get_json()


def test_new_expense_notifies_only_the_counterparty(client: FlaskClient, app: Flask) -> None:
    sender = FakeSender()
    _install(app, sender)
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob")
    _register_device(client, alice_token, "alice-device")
    _register_device(client, bob_token, "bob-device")

    make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=4000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2000},
            {"user_id": bob["id"], "amount_cents": 2000},
        ],
    )

    assert len(sender.messages) == 1
    msg = sender.messages[0]
    # Bob is the counterparty; Alice (the creator) is not notified.
    assert msg.tokens == ["bob-device"]
    assert msg.data["type"] == "expense_pending"
    assert msg.data["entry_kind"] == "expense"
    assert msg.data["relationship_id"] == str(rel["id"])
    assert "Alice" in msg.body
    assert "40.00 USD" in msg.body


def test_confirming_expense_notifies_creator(client: FlaskClient, app: Flask) -> None:
    sender = FakeSender()
    _install(app, sender)
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob")
    _register_device(client, alice_token, "alice-device")
    _register_device(client, bob_token, "bob-device")

    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=4000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2000},
            {"user_id": bob["id"], "amount_cents": 2000},
        ],
    )
    sender.messages.clear()

    resp = client.post(
        f"/api/v1/expenses/{expense['id']}/confirm",
        headers=auth_headers(bob_token),
    )
    assert resp.status_code == 200

    assert len(sender.messages) == 1
    msg = sender.messages[0]
    # The creator (Alice) is notified that Bob confirmed.
    assert msg.tokens == ["alice-device"]
    assert msg.data["type"] == "expense_confirmed"
    assert "Bob" in msg.body
    assert "40.00 USD" in msg.body


def test_payment_create_and_confirm_notify(client: FlaskClient, app: Flask) -> None:
    sender = FakeSender()
    _install(app, sender)
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob")
    _register_device(client, alice_token, "alice-device")
    _register_device(client, bob_token, "bob-device")

    payment = make_payment(
        client,
        bob_token,
        relationship_id=rel["id"],
        from_user_id=bob["id"],
        to_user_id=alice["id"],
        amount_cents=2500,
    )
    assert len(sender.messages) == 1
    created = sender.messages[0]
    assert created.tokens == ["alice-device"]
    assert created.data["type"] == "payment_pending"
    assert "25.00 USD" in created.body

    sender.messages.clear()
    resp = client.post(
        f"/api/v1/payments/{payment['id']}/confirm",
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 200
    assert len(sender.messages) == 1
    confirmed = sender.messages[0]
    assert confirmed.tokens == ["bob-device"]
    assert confirmed.data["type"] == "payment_confirmed"


def test_no_registered_device_sends_nothing(client: FlaskClient, app: Flask) -> None:
    sender = FakeSender()
    _install(app, sender)
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, _bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob")
    # No devices registered.

    make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=4000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2000},
            {"user_id": bob["id"], "amount_cents": 2000},
        ],
    )
    assert sender.messages == []


def test_send_failure_does_not_break_the_write(client: FlaskClient, app: Flask) -> None:
    _install(app, BoomSender())
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob")
    _register_device(client, bob_token, "bob-device")

    # A failing sender must not surface as a failed expense — make_expense
    # asserts the create returned 201.
    expense = make_expense(
        client,
        alice_token,
        relationship_id=rel["id"],
        payer_user_id=alice["id"],
        total_cents=4000,
        shares=[
            {"user_id": alice["id"], "amount_cents": 2000},
            {"user_id": bob["id"], "amount_cents": 2000},
        ],
    )
    assert expense["status"] == "pending"
