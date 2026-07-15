# API Reference

Parity exposes a RESTful API over HTTPS. All endpoints (except health, auth/login, and auth/register) require a `Bearer` token. Timestamps are UTC ISO 8601 with a `Z` suffix. Currency amounts are always integers representing cents. All endpoints are versioned under `/api/v1`.

## Health
- `GET /api/v1/health`: Liveness check (no auth).

## Authentication
- `POST /api/v1/auth/register`: Register a new user. Accepts an optional `email` (used only for password recovery; unique across accounts).
- `POST /api/v1/auth/login`: Authenticate and receive a token.
- `POST /api/v1/auth/logout`: Revoke current token.
- `GET /api/v1/auth/me`: Get the signed-in user (self-view includes `is_admin` and `email`).
- `PATCH /api/v1/auth/me`: Update the signed-in user's `display_name` and/or `email` (a null/blank `email` clears it). Returns the self-view.
- `POST /api/v1/auth/change-password`: Change password; revokes all other sessions.
- `POST /api/v1/auth/refresh`: Exchange the current token for a fresh one.

### Password reset
A recovery email must be on file (see `email` above). Email delivery is transport-agnostic and off until SMTP is configured (see [ADR-0002](adr/0002-password-reset-transport.md)); until then requests succeed but deliver nothing.
- `POST /api/v1/auth/password-reset/request`: Body `{email}`. Always returns `204` — it never reveals whether an address is registered. If a live account owns the address, a single-use, short-lived reset token is emailed.
- `POST /api/v1/auth/password-reset/confirm`: Body `{token, new_password}`. Sets the new password on a valid, unexpired, unused token and **revokes every session** for that account. Errors: `invalid_token`, `weak_password`.

## Relationships
- `GET /api/v1/relationships`: List all relationships.
- `POST /api/v1/relationships`: Invite a user to form a relationship.
- `POST /api/v1/relationships/<id>/accept`: Accept a pending relationship invite.
- `POST /api/v1/relationships/<id>/reject`: Reject a pending invite.
- `GET /api/v1/relationships/<id>/balance`: Get confirmed and projected balances.

## Ledger (Expenses & Payments)
- `GET /api/v1/expenses`: List expenses (accepts a `status` filter + pagination).
- `GET /api/v1/expenses/<id>`: Fetch a single expense.
- `POST /api/v1/expenses`: Create an expense (supports optional `category`).
- `POST /api/v1/expenses/<id>/confirm`: Confirm a pending expense (counterparty only).
- `POST /api/v1/expenses/<id>/discard`: Discard a pending expense.
- `POST /api/v1/expenses/<id>/reverse`: Reverse a confirmed expense.

- `GET /api/v1/payments`: List payments (accepts a `status` filter + pagination).
- `GET /api/v1/payments/<id>`: Fetch a single payment.
- `POST /api/v1/payments`: Create a payment.
- `POST /api/v1/payments/<id>/confirm`: Confirm a pending payment (counterparty only).
- `POST /api/v1/payments/<id>/discard`: Discard a pending payment.
- `POST /api/v1/payments/<id>/reverse`: Reverse a confirmed payment.

## Pending
- `GET /api/v1/pending`: Expenses and payments across all relationships awaiting the caller's confirmation, for the dashboard "needs you" view. Returns `{"expenses": [...], "payments": [...]}`.

## Recurring expenses
Templates that generate pending expenses on a schedule. The generated entries appear through the normal expense endpoints; these routes only manage the templates.
- `GET /api/v1/recurring`: List recurring templates (accepts `relationship_id`, `active=true|false`, and pagination).
- `GET /api/v1/recurring/<id>`: Fetch a single template.
- `POST /api/v1/recurring`: Create a template (`relationship_id`, `payer_user_id`, `total_cents`, `description`, `shares`, `interval` one of `daily`/`weekly`/`monthly`, optional `category` and `start_on` date defaulting to today).
- `PATCH /api/v1/recurring/<id>`: Update a template — pause/resume (`active`), reschedule (`next_run_on`), or edit fields (`total_cents` + `shares` must change together).
- `DELETE /api/v1/recurring/<id>`: Delete a template.

Generation is driven by the `flask run-recurring` CLI command (intended for a daily cron), which materialises one pending expense per due template.

## Comments
- `GET /api/v1/expenses/<id>/comments`: List comments on an expense.
- `POST /api/v1/expenses/<id>/comments`: Post a comment on an expense.
- `GET /api/v1/payments/<id>/comments`: List comments on a payment.
- `POST /api/v1/payments/<id>/comments`: Post a comment on a payment.

## Attachments
Receipt photos or PDFs attached to an expense (see [ADR-0003](adr/0003-attachment-storage.md)). File bytes live in object storage; only metadata is in the DB. Both parties to the expense's relationship can view and upload; the uploader can delete. Allowed types: `image/jpeg`, `image/png`, `image/webp`, `image/heic`, `application/pdf`; max size `ATTACHMENT_MAX_BYTES` (default 10 MB).
- `POST /api/v1/expenses/<id>/attachments`: Upload a file (`multipart/form-data`, field `file`). Returns the attachment metadata.
- `GET /api/v1/expenses/<id>/attachments`: List attachment metadata for an expense.
- `GET /api/v1/attachments/<id>`: Download the file bytes (streamed with its content-type).
- `DELETE /api/v1/attachments/<id>`: Delete an attachment (uploader only); removes the object.

## Admin
Operator-only; all require the signed-in user to be an admin.
- `GET /api/v1/admin/stats`: Row counts across the database.
- `POST /api/v1/admin/cleanup-tokens`: Purge expired/revoked auth tokens.
- `POST /api/v1/admin/reset-ledger`: Erase all ledger entries (requires a confirmation phrase); accounts and relationships are preserved.
