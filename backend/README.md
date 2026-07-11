# Parity Backend

The backend for **Parity** — a self-hosted, two-party expense and payment
tracking ledger. This package exposes a Flask REST API; an Android client
will consume it in a later phase.

Through **Phase 5**, the backend covers auth (with password change,
token refresh, and idle + absolute token expiry), relationships
(including a required per-relationship `currency_code`, the bundled
invite + first-entry flow for both expenses and payments, and cascade
discard on rejection),
expenses, payments, balance computation, paginated list endpoints,
the Phase 3 hardening pass (DB-level immutability triggers, request
rate limiting, login response-timing equalisation), and rate limits
on the new auth endpoints. The Android client is the remaining
roadmap item.

## Requirements

- Python 3.12+

## Setup

```bash
cd backend
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

Copy the example env file and fill in `SECRET_KEY`:

```bash
cp .env.example .env
```

Generate a random secret key:

```bash
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Paste the value into `.env` in place of `<REPLACE_WITH_RANDOM_STRING>`.

## Create the database

```bash
flask db upgrade
```

This creates `backend/instance/parity.db` with the full schema (Phase 1
initial migration, the Phase 2 migration that renames the relationship
party columns, adds the `rejected` status, and installs the partial
expression unique index, the Phase 3 migration that installs the
immutability triggers on the ledger tables, the Phase 4 migration
that adds an `expires_at` column to `auth_token`, and the Phase 5
migration that adds the required `currency_code` column to
`relationship` and extends the relationship immutability trigger).

## Run the server

```bash
flask run
```

The API is then available at `http://localhost:5000`. This is the
Flask development server — for a real deployment, use Docker (below).

## Deploy with Docker

The repo root ships a production image (`backend/Dockerfile`) and a
compose stack (`compose.yaml`). The image runs the app under **gunicorn**
as a non-root user, applies migrations on startup, and stores the SQLite
database in a named volume (`/data/parity.db`).

```bash
cp .env.example .env      # then set SECRET_KEY (see the file)
docker compose up -d app  # build + run the API on 127.0.0.1:8000
```

Front it with the bundled Caddy reverse proxy (automatic HTTPS) by
setting `PARITY_DOMAIN` in `.env` and enabling the `edge` profile:

```bash
docker compose --profile edge up -d
```

Override the host port with `APP_PORT` and worker count with
`WEB_CONCURRENCY`. Note SQLite is the scale ceiling — a busy deployment
with many writers should move to Postgres via `DATABASE_URL`.

## Run tests

```bash
pytest
```

Tests run against an in-memory SQLite database and a fresh schema per test.

## Run lint and format checks

```bash
ruff check .
ruff format --check .
```

## API surface

All endpoints live under `/api/v1`. Every endpoint except `register`,
`login`, and `health` requires a `Bearer` token in the
`Authorization` header.

| Method | Path | Purpose |
| ------ | ---- | ------- |
| `GET`  | `/health` | Service + DB liveness. |
| `POST` | `/auth/register` | Create a user. |
| `POST` | `/auth/login` | Exchange credentials for a bearer token. |
| `POST` | `/auth/logout` | Revoke the calling token. |
| `POST` | `/auth/refresh` | Issue a fresh token, revoke the request token. |
| `POST` | `/auth/change-password` | Change the caller's password; revokes other sessions. |
| `GET`  | `/auth/me` | Return the calling user (includes `is_admin` for the caller only). |
| `PATCH` | `/auth/me` | Update the caller's profile (`display_name`). |
| `GET`  | `/auth/me/export` | Machine-readable JSON dump of the caller's account (user, relationships, expenses, payments, comments). |
| `DELETE` | `/auth/me` | Delete (anonymize) the caller's account; requires the password in the body. See below. |
| `POST` | `/relationships` | Invite another user (optionally with a bundled first expense). |
| `GET`  | `/relationships?status=&limit=&offset=` | List relationships visible to the caller. |
| `GET`  | `/relationships/{id}` | Fetch one relationship. |
| `POST` | `/relationships/{id}/accept` | Invited user accepts. |
| `POST` | `/relationships/{id}/reject` | Either party rejects/withdraws (cascade-discards pending expenses). |
| `GET`  | `/relationships/{id}/balance` | Confirmed and projected balance. |
| `POST` | `/expenses` | Create a pending expense with shares. |
| `GET`  | `/expenses?relationship_id=&status=&limit=&offset=` | List expenses visible to the caller. |
| `GET`  | `/expenses/{id}` | Fetch one expense with its shares. |
| `POST` | `/expenses/{id}/confirm` | Counterparty confirms a pending expense. |
| `POST` | `/expenses/{id}/discard` | Either party discards (optional `reason`). |
| `POST` | `/expenses/{id}/reverse` | Create a pending reversal of a confirmed expense. |
| `POST` | `/payments` | Create a pending payment. |
| `GET`  | `/payments?relationship_id=&status=&limit=&offset=` | List payments visible to the caller. |
| `GET`  | `/payments/{id}` | Fetch one payment. |
| `POST` | `/payments/{id}/confirm` | Counterparty confirms a pending payment. |
| `POST` | `/payments/{id}/discard` | Either party discards (optional `reason`). |
| `POST` | `/payments/{id}/reverse` | Create a pending reversal of a confirmed payment. |
| `GET`  | `/expenses/{id}/comments` | List comments on an expense. |
| `POST` | `/expenses/{id}/comments` | Comment on an expense. |
| `GET`  | `/payments/{id}/comments` | List comments on a payment. |
| `POST` | `/payments/{id}/comments` | Comment on a payment. |
| `GET`  | `/admin/stats` | Admin only: row counts (users, entries, tokens). |
| `POST` | `/admin/cleanup-tokens` | Admin only: purge expired/revoked tokens. |
| `POST` | `/admin/reset-ledger` | Admin only: erase all ledger entries (see below). |

Admin endpoints return `404 not_found` for non-admin callers so the
admin surface doesn't leak, mirroring the non-party convention on
relationship-scoped routes.

### Administration

The system-administrator account is created from the server shell —
there is no API path to admin, which is what scopes it to the
operator:

```bash
cd backend
SECRET_KEY=... flask create-admin root          # prints the access key once
SECRET_KEY=... flask create-admin root --rotate-key   # new key, revokes sessions
```

The printed access key is a machine-generated 256-bit secret used as
the password on the normal login flow. Sign in with it in the app and
a **System administration** panel appears in Settings.

`POST /admin/reset-ledger` erases every expense, share, payment, and
comment (users, relationships, and the audit log survive; the reset is
itself audited). It deliberately punches through the DB-level
immutability triggers by dropping the three delete-guards, deleting,
and reinstalling them in one transaction. The request body must be
`{"confirm": "RESET LEDGER"}` or the call fails with
`confirmation_required`. The same operation is available offline via:

```bash
SECRET_KEY=... flask reset-ledger --yes-i-mean-it
```

### Response envelope

Single-resource successes return the resource object directly. Lists
return `{"items": [...], "total": N, "limit": L, "offset": O,
"has_more": bool}`. Errors use:

```json
{"error": {"code": "snake_case_identifier", "message": "...", "details": { }}}
```

`details` is present only when it carries information beyond `message`
(for example, `share_sum_mismatch` includes the offending totals).
Timestamps are ISO 8601 with a `Z` suffix.

The single exception to the single-resource rule is `POST
/relationships` when the request includes `first_expense` or
`first_payment`: the response is a two-resource envelope
`{"relationship": {...}, "expense": {...}}` (or `{"relationship":
{...}, "payment": {...}}`) so the bundled invite + first-entry flow
can return both rows in one round trip.

### Invite

`POST /relationships` requires `username` and `currency_code` in the
body. `currency_code` must match `^[A-Z]{3}$` (three uppercase ASCII
letters); the server does not enforce ISO 4217 membership beyond
format — the client is responsible for offering a curated list. The
currency is fixed at relationship creation: every expense and payment
on the relationship is in that currency, there is no per-entry
override, and the column is immutable once written (enforced both by
the application and by a DB trigger).

`first_expense` is an optional field on the same request:

```json
{
  "username": "bob",
  "currency_code": "USD",
  "first_expense": {
    "payer_user_id": 1,
    "total_cents": 5000,
    "description": "Lunch at Pat's",
    "shares": [
      {"user_id": 1, "amount_cents": 2500},
      {"user_id": 2, "amount_cents": 2500}
    ]
  }
}
```

A missing or null `currency_code` returns `400 bad_request`; a
present-but-malformed value returns `422 invalid_currency_code` with
`details.value` echoing the supplied string.

When `first_expense` is present, the relationship and expense rows
(plus shares) are inserted in a single transaction. A validation
failure on the expense rolls back the relationship. The expense
validations match the standalone `POST /expenses` codes; the only
expense check skipped is the `relationship_not_accepted` 409, because
the bundled relationship is freshly created and still pending.

When `first_expense` is omitted, the endpoint returns the
relationship object directly.

`first_payment` is the payment-flavoured alternative on the same
request, parallel to `first_expense`:

```json
{
  "username": "bob",
  "currency_code": "USD",
  "first_payment": {
    "from_user_id": 1,
    "to_user_id": 2,
    "amount_cents": 5000,
    "description": "Paid you back"
  }
}
```

A payment is an independent ledger entry — money moving between the
two parties — not a settlement of any particular expense, so seeding a
brand-new relationship with a first payment is meaningful on its own.
When `first_payment` is present, the relationship and payment rows are
inserted in a single transaction; a validation failure on the payment
rolls back the relationship. The payment validations match the
standalone `POST /payments` codes (`invalid_amount`, `invalid_party`,
`same_parties`), with the same `relationship_not_accepted` carve-out as
`first_expense`.

`first_expense` and `first_payment` are mutually exclusive: the
bundled flow seeds a relationship with exactly one initial entry.
Supplying both returns `422 both_first_entries` and writes nothing.
When `first_payment` is present, the response envelope is
`{"relationship": {...}, "payment": {...}}`.

Rejecting a relationship cascade-discards any pending expenses *and
payments* still attached to it. The discarded rows carry
`rejection_reason: "Relationship rejected"` and `discarded_by_user_id`
set to the rejecter; confirmed entries are never affected (they cannot
exist on a non-accepted relationship anyway).

### Pagination

`GET /relationships`, `GET /expenses`, and `GET /payments` accept
`limit` (default 50, range `[1, 200]`) and `offset` (default 0, must
be `>= 0`). Both compose with the existing `status` and
`relationship_id` filters. Out-of-range or non-integer values return
`422 invalid_pagination` with `details: {"parameter": "<limit |
offset>", "value": "<as-supplied>"}`.

Response fields:

- `items` — the page of serialised rows in the existing per-row shape.
- `total` — count of rows matching the filters before pagination.
- `limit`, `offset` — echoed back from the request (or the defaults).
- `has_more` — `offset + len(items) < total`.

Ordering is `created_at DESC, id DESC` on all three endpoints; the
`id` tiebreaker keeps pages stable under concurrent inserts.

### Password change

`POST /auth/change-password` requires auth.

```json
{"current_password": "...", "new_password": "..."}
```

Returns `204 No Content` on success. The calling token stays alive;
every other un-revoked token for the same user is revoked in the same
transaction. Distinct 422 codes — `invalid_request` (missing fields),
`weak_password` (new password under 8 chars, with
`details.min_length`), and `invalid_current_password` — let a client
show specific UI without inferring from the message.

### Account export and deletion

`GET /auth/me/export` requires auth and returns a machine-readable JSON
dump of everything tied to the caller: their user record, the
relationships they belong to, and all expenses, payments, and
comments they authored or are a party to.

`DELETE /auth/me` requires auth and the account password in the body:

```json
{"password": "..."}
```

Returns `204 No Content` on success; a missing or wrong password
returns `403 invalid_password` and changes nothing. Deletion is
**anonymization, not a hard delete** — the caller's ledger entries are
also part of the counterparty's financial record, so removing them
would corrupt the other party's confirmed balance. Instead the row is
retained but its identity is scrubbed: `username` becomes
`deleted_user_<id>`, `display_name` becomes `Deleted user`, the
password hash is replaced with a fresh random value, `is_admin` is
cleared, `deleted_at` is stamped, and every outstanding token is
revoked. Login and all authenticated requests then reject the account
(login keeps the constant-time dummy-verify so deletion doesn't reopen
username enumeration), and the freed username can be registered again.

### Refresh

`POST /auth/refresh` requires auth, takes no body, and returns the
same envelope as `POST /auth/login`:

```json
{"token": "...", "user": {"id": ..., "username": "...", "display_name": "..."}}
```

The request's token is revoked in the same transaction that issues
the new one. There is no automatic rotation on every authenticated
request — refresh is explicit only.

### Token expiration

Every `auth_token` row carries an `expires_at` (the absolute hard
cap, set to `created_at + TOKEN_ABSOLUTE_LIFETIME_DAYS` at insert)
and a sliding idle window (`last_used_at + TOKEN_IDLE_LIFETIME_DAYS`).
A request that fails either check returns `401 token_expired`;
unknown, malformed, or revoked tokens continue to return
`401 unauthorized` as in Phases 1–3.

## Example curl flow

```bash
BASE=http://localhost:5000/api/v1

# 1. Register two users.
curl -s -X POST $BASE/auth/register -H 'Content-Type: application/json' \
    -d '{"username":"alice","password":"pw-alice","display_name":"Alice"}'
curl -s -X POST $BASE/auth/register -H 'Content-Type: application/json' \
    -d '{"username":"bob","password":"pw-bob","display_name":"Bob"}'

# 2. Log in as each and capture tokens.
A_TOK=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"alice","password":"pw-alice"}' \
    | python -c 'import json,sys;print(json.load(sys.stdin)["token"])')
B_TOK=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"bob","password":"pw-bob"}' \
    | python -c 'import json,sys;print(json.load(sys.stdin)["token"])')

# 3. Alice invites Bob; capture the relationship id.
REL=$(curl -s -X POST $BASE/relationships -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $A_TOK" -d '{"username":"bob","currency_code":"USD"}' \
    | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')

# 4. Bob accepts.
curl -s -X POST $BASE/relationships/$REL/accept -H "Authorization: Bearer $B_TOK"

# 5. Alice creates a $100 expense, evenly split.
EID=$(curl -s -X POST $BASE/expenses -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $A_TOK" \
    -d "{\"relationship_id\":$REL,\"payer_user_id\":1,\"total_cents\":10000,
         \"description\":\"Dinner\",\"shares\":[
           {\"user_id\":1,\"amount_cents\":5000},
           {\"user_id\":2,\"amount_cents\":5000}]}" \
    | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')

# 6. Bob confirms the expense.
curl -s -X POST $BASE/expenses/$EID/confirm -H "Authorization: Bearer $B_TOK"

# 7. Balance: Bob owes Alice $50.
curl -s $BASE/relationships/$REL/balance -H "Authorization: Bearer $A_TOK"

# 8. Bob pays Alice $30 (pending), Alice confirms.
PID=$(curl -s -X POST $BASE/payments -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $B_TOK" \
    -d "{\"relationship_id\":$REL,\"from_user_id\":2,\"to_user_id\":1,
         \"amount_cents\":3000,\"description\":\"Venmo\"}" \
    | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')
curl -s -X POST $BASE/payments/$PID/confirm -H "Authorization: Bearer $A_TOK"

# 9. Balance now: Bob owes Alice $20.
curl -s $BASE/relationships/$REL/balance -H "Authorization: Bearer $A_TOK"

# 10. Bob reverses the original expense; Alice confirms the reversal.
REVID=$(curl -s -X POST $BASE/expenses/$EID/reverse \
    -H "Authorization: Bearer $B_TOK" \
    | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')
curl -s -X POST $BASE/expenses/$REVID/confirm -H "Authorization: Bearer $A_TOK"

# 11. Final balance: Alice owes Bob $30 (the overpayment).
curl -s $BASE/relationships/$REL/balance -H "Authorization: Bearer $A_TOK"
```

## Health check

```bash
curl -s http://localhost:5000/api/v1/health
# -> {"status":"ok","database":"ok"}
```

## Database integrity

Phase 3 installs a set of SQLite triggers as a DB-level backstop for
the ledger invariants the service layer already enforces. The triggers
are not expected to fire under normal operation; their value is
catching service-layer bugs and preventing damage from direct SQL.

| Trigger | Fires when |
| ------- | ---------- |
| `trg_expense_no_delete`                 | Any `DELETE` on `expense`. |
| `trg_expense_no_update_terminal`        | Any `UPDATE` on a non-`pending` `expense`. |
| `trg_expense_immutable_columns`         | Any `UPDATE` to `payer_user_id`, `relationship_id`, `total_cents`, `description`, `created_by_user_id`, `created_at`, or `reverses_expense_id` on `expense`. |
| `trg_expense_share_no_update`           | Any `UPDATE` on `expense_share`. |
| `trg_expense_share_no_delete`           | Any `DELETE` on `expense_share`. |
| `trg_payment_no_delete`                 | Any `DELETE` on `payment`. |
| `trg_payment_no_update_terminal`        | Any `UPDATE` on a non-`pending` `payment`. |
| `trg_payment_immutable_columns`         | Any `UPDATE` to `from_user_id`, `to_user_id`, `relationship_id`, `amount_cents`, `description`, `created_by_user_id`, `created_at`, or `reverses_payment_id` on `payment`. |
| `trg_relationship_no_delete`            | Any `DELETE` on `relationship`. |
| `trg_relationship_no_update_terminal`   | Any `UPDATE` on a non-`pending` `relationship`. |
| `trg_relationship_immutable_columns`    | Any `UPDATE` to `inviting_user_id`, `invited_user_id`, `created_at`, or `currency_code` on `relationship`. |

The legitimate `pending → confirmed` and `pending → discarded`
transitions (touching only `status`, the timestamp, and the actor
columns) are unaffected. Re-invites after a rejection still work
through `INSERT`, because rejection rows are terminal but `INSERT` is
not gated by any trigger.

Trigger errors surface to SQLAlchemy as `IntegrityError` and propagate
back as HTTP 500 — they're a bug indicator, not a routine code path.

## Environment variables

Read at app startup via `os.environ`. The defaults are tuned for a
single self-hosted instance.

| Variable | Default | Purpose |
| -------- | ------- | ------- |
| `SECRET_KEY`                    | _(required)_      | Flask session secret. |
| `DATABASE_URL`                  | `sqlite:///parity.db` | SQLAlchemy URI; relative SQLite paths land under `backend/instance/`. |
| `FLASK_ENV`                     | `development`     | Selects `Development`/`Testing`/`Production` config class. |
| `RATELIMIT_STORAGE_URI`         | `memory://`       | Flask-Limiter storage backend. In-process is sufficient for a single instance. |
| `RATELIMIT_LOGIN_IP`            | `5 per minute`    | Per remote IP cap on `POST /auth/login`. |
| `RATELIMIT_LOGIN_USERNAME`      | `20 per hour`     | Per `username` cap on `POST /auth/login` — defeats credential-stuffing against a single account from rotating IPs. |
| `RATELIMIT_REGISTER`            | `5 per hour`      | Per remote IP cap on `POST /auth/register`. |
| `RATELIMIT_WRITE`               | `60 per minute`   | Per authenticated user cap on all `POST` endpoints under `relationships`, `expenses`, and `payments`. |
| `RATELIMIT_CHANGE_PASSWORD`     | `5 per hour`      | Per authenticated user cap on `POST /auth/change-password`. |
| `RATELIMIT_REFRESH`             | `10 per hour`     | Per authenticated user cap on `POST /auth/refresh`. |
| `TOKEN_ABSOLUTE_LIFETIME_DAYS`  | `365`             | Hard cap from a token's `created_at`. Past this, requests return `401 token_expired`. |
| `TOKEN_IDLE_LIFETIME_DAYS`      | `30`              | Sliding cap from `last_used_at`. Past this without a successful request, the token expires. |

`GET` endpoints are not rate-limited. Hitting any limit returns HTTP
429 with the standard error envelope (`code: "rate_limited"`, plus
`details.limit` carrying the human-readable limit string) and a
`Retry-After` header.

## Design notes

- **Immutable ledger.** Confirmed expenses and payments are never edited
  or deleted; corrections are reversing entries that reference the
  original via `reverses_*_id`. Enforcement is application-layer in the
  service code, with DB-level triggers (Phase 3) as a backstop — see
  "Database integrity" above.
- **Two-party confirmation.** Entries are `pending` until the counterparty
  confirms; pending entries do not affect balance.
- **N-party future-proof, two-party UX.** The schema supports many users
  per expense via `expense_share`; v1 flows are two-party only.
- **Integer cents only.** Money is stored and returned as integer cents.
- **UTC in DB.** All timestamps are stored as timezone-aware UTC and
  serialised with a `Z` suffix.
- **Symmetric uniqueness via partial expression index.** At most one
  non-rejected relationship can exist between a given pair of users in
  either direction. Rejected rows are excluded from the index, so a
  prior rejection does not prevent a fresh invite.
- **Balance has two views.** `confirmed` reflects only confirmed
  entries; `projected` additionally folds in pending entries as if
  they were confirmed. Discarded entries are excluded from both.

## Project layout

```
backend/
├── app/
│   ├── __init__.py        # application factory
│   ├── config.py
│   ├── extensions.py      # db, migrate
│   ├── errors.py          # JSON error envelope + handlers
│   ├── api/               # health, relationships, expenses, payments
│   ├── auth/              # register / login / logout / me, decorators, hashing
│   ├── models/            # User, Relationship, Expense, ExpenseShare, Payment, AuthToken
│   └── services/          # relationships, expenses, payments, balance — validation + mutation
├── migrations/            # Alembic, generated by Flask-Migrate
├── tests/
├── pyproject.toml
└── .env.example
```
