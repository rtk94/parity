"""Attachment endpoint tests (upload, list, download, delete, authz)."""

from __future__ import annotations

import hashlib
import io
from typing import Any

from flask import Flask
from flask.testing import FlaskClient

from app.services.object_store import ObjectNotFound
from tests.factories import auth_headers, make_expense, make_logged_in_user, make_relationship

_PNG = b"\x89PNG\r\n\x1a\n" + b"fake-image-bytes" * 4


class _MemoryStore:
    """In-memory object store so tests never touch disk."""

    def __init__(self) -> None:
        self.objects: dict[str, tuple[bytes, str]] = {}

    def put(self, key: str, data: bytes, content_type: str) -> None:
        self.objects[key] = (data, content_type)

    def get(self, key: str) -> bytes:
        if key not in self.objects:
            raise ObjectNotFound(key)
        return self.objects[key][0]

    def delete(self, key: str) -> None:
        self.objects.pop(key, None)


def _store(app: Flask) -> _MemoryStore:
    store = _MemoryStore()
    app.extensions["object_store"] = store
    return store


def _two_party(client: FlaskClient) -> tuple[dict, dict, str, str, dict]:
    alice, alice_token = make_logged_in_user(client, "alice")
    bob, bob_token = make_logged_in_user(client, "bob")
    rel = make_relationship(client, alice_token, "bob", accept=True)
    return alice, bob, alice_token, bob_token, rel


def _an_expense(client: FlaskClient, alice: dict, bob: dict, alice_token: str, rel: dict) -> dict:
    return make_expense(
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


def _upload(
    client: FlaskClient,
    token: str,
    expense_id: int,
    *,
    data: bytes = _PNG,
    filename: str = "receipt.png",
    content_type: str = "image/png",
) -> Any:
    payload: dict[str, Any] = {}
    if filename is not None:
        payload["file"] = (io.BytesIO(data), filename, content_type)
    return client.post(
        f"/api/v1/expenses/{expense_id}/attachments",
        data=payload,
        content_type="multipart/form-data",
        headers=auth_headers(token),
    )


# --- upload -----------------------------------------------------------------


def test_upload_happy_path(app: Flask, client: FlaskClient) -> None:
    store = _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)

    resp = _upload(client, alice_token, expense["id"])
    assert resp.status_code == 201, resp.get_json()
    body = resp.get_json()
    assert body["filename"] == "receipt.png"
    assert body["content_type"] == "image/png"
    assert body["size_bytes"] == len(_PNG)
    assert body["checksum_sha256"] == hashlib.sha256(_PNG).hexdigest()
    assert body["uploaded_by_user_id"] == alice["id"]
    # Bytes actually landed in the store.
    assert len(store.objects) == 1


def test_upload_unsupported_type(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    resp = _upload(
        client,
        alice_token,
        expense["id"],
        data=b"#!/bin/sh\n",
        filename="x.sh",
        content_type="application/x-sh",
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "unsupported_type"


def test_upload_empty_file(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    resp = _upload(client, alice_token, expense["id"], data=b"")
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "empty_file"


def test_upload_too_large(app: Flask, client: FlaskClient) -> None:
    _store(app)
    app.config["ATTACHMENT_MAX_BYTES"] = 16
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    resp = _upload(client, alice_token, expense["id"], data=b"x" * 32)
    assert resp.status_code == 422
    assert resp.get_json()["error"]["code"] == "file_too_large"


def test_upload_missing_file_field(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    resp = client.post(
        f"/api/v1/expenses/{expense['id']}/attachments",
        data={},
        content_type="multipart/form-data",
        headers=auth_headers(alice_token),
    )
    assert resp.status_code == 400


def test_upload_non_party_is_404(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    _carol, carol_token = make_logged_in_user(client, "carol")
    resp = _upload(client, carol_token, expense["id"])
    assert resp.status_code == 404


# --- list / download --------------------------------------------------------


def test_list_and_download(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    uploaded = _upload(client, alice_token, expense["id"]).get_json()

    # Both parties can list.
    listing = client.get(
        f"/api/v1/expenses/{expense['id']}/attachments", headers=auth_headers(bob_token)
    )
    assert listing.status_code == 200
    items = listing.get_json()["items"]
    assert len(items) == 1
    assert items[0]["id"] == uploaded["id"]

    # The counterparty (not the uploader) can download the bytes.
    dl = client.get(f"/api/v1/attachments/{uploaded['id']}", headers=auth_headers(bob_token))
    assert dl.status_code == 200
    assert dl.data == _PNG
    assert dl.headers["Content-Type"] == "image/png"
    assert "receipt.png" in dl.headers["Content-Disposition"]


def test_download_non_party_is_404(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    uploaded = _upload(client, alice_token, expense["id"]).get_json()
    _carol, carol_token = make_logged_in_user(client, "carol")
    resp = client.get(f"/api/v1/attachments/{uploaded['id']}", headers=auth_headers(carol_token))
    assert resp.status_code == 404


def test_download_missing_id_is_404(app: Flask, client: FlaskClient) -> None:
    _store(app)
    _alice, alice_token = make_logged_in_user(client, "alice")
    resp = client.get("/api/v1/attachments/999999", headers=auth_headers(alice_token))
    assert resp.status_code == 404


# --- delete -----------------------------------------------------------------


def test_uploader_can_delete(app: Flask, client: FlaskClient) -> None:
    store = _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    uploaded = _upload(client, alice_token, expense["id"]).get_json()

    resp = client.delete(f"/api/v1/attachments/{uploaded['id']}", headers=auth_headers(alice_token))
    assert resp.status_code == 204
    # Row and object both gone.
    assert store.objects == {}
    gone = client.get(f"/api/v1/attachments/{uploaded['id']}", headers=auth_headers(alice_token))
    assert gone.status_code == 404


def test_non_uploader_party_cannot_delete(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    uploaded = _upload(client, alice_token, expense["id"]).get_json()

    # Bob is a party but not the uploader.
    resp = client.delete(f"/api/v1/attachments/{uploaded['id']}", headers=auth_headers(bob_token))
    assert resp.status_code == 403
    assert resp.get_json()["error"]["code"] == "not_uploader"


def test_missing_object_surfaces_404(app: Flask, client: FlaskClient) -> None:
    store = _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    uploaded = _upload(client, alice_token, expense["id"]).get_json()
    # Simulate a compensation gap: the row exists but the object is gone.
    store.objects.clear()
    resp = client.get(f"/api/v1/attachments/{uploaded['id']}", headers=auth_headers(alice_token))
    assert resp.status_code == 404


# --- export -----------------------------------------------------------------


def test_export_includes_attachments(app: Flask, client: FlaskClient) -> None:
    _store(app)
    alice, bob, alice_token, _bob_token, rel = _two_party(client)
    expense = _an_expense(client, alice, bob, alice_token, rel)
    _upload(client, alice_token, expense["id"])
    export = client.get("/api/v1/auth/me/export", headers=auth_headers(alice_token)).get_json()
    assert len(export["attachments"]) == 1
    assert export["attachments"][0]["expense_id"] == expense["id"]
