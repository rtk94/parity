# Parity Backend

The backend for **Parity** — a self-hosted, two-party expense and payment
tracking ledger. This package exposes a Flask REST API; an Android client
will consume it in a later phase.

Through **Phase 2**, the backend covers auth, relationships, expenses,
payments, and balance computation. The remaining roadmap is hardening
(DB-level immutability triggers, rate limiting, etc.) and the Android
client.

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
initial migration plus the Phase 2 migration that renames the
relationship party columns, adds the `rejected` status, and installs
the partial expression unique index).

## Run the server

```bash
flask run
```

The API is then available at `http://localhost:5000`.

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
| `GET`  | `/auth/me` | Return the calling user. |
| `POST` | `/relationships` | Invite another user by username. |
| `GET`  | `/relationships?status=` | List relationships visible to the caller. |
| `GET`  | `/relationships/{id}` | Fetch one relationship. |
| `POST` | `/relationships/{id}/accept` | Invited user accepts. |
| `POST` | `/relationships/{id}/reject` | Either party rejects/withdraws. |
| `GET`  | `/relationships/{id}/balance` | Confirmed and projected balance. |
| `POST` | `/expenses` | Create a pending expense with shares. |
| `GET`  | `/expenses?relationship_id=&status=` | List expenses visible to the caller. |
| `GET`  | `/expenses/{id}` | Fetch one expense with its shares. |
| `POST` | `/expenses/{id}/confirm` | Counterparty confirms a pending expense. |
| `POST` | `/expenses/{id}/discard` | Either party discards (optional `reason`). |
| `POST` | `/expenses/{id}/reverse` | Create a pending reversal of a confirmed expense. |
| `POST` | `/payments` | Create a pending payment. |
| `GET`  | `/payments?relationship_id=&status=` | List payments visible to the caller. |
| `GET`  | `/payments/{id}` | Fetch one payment. |
| `POST` | `/payments/{id}/confirm` | Counterparty confirms a pending payment. |
| `POST` | `/payments/{id}/discard` | Either party discards (optional `reason`). |
| `POST` | `/payments/{id}/reverse` | Create a pending reversal of a confirmed payment. |

### Response envelope

Single-resource successes return the resource object directly. Lists
return `{"items": [...]}`. Errors use:

```json
{"error": {"code": "snake_case_identifier", "message": "...", "details": { }}}
```

`details` is present only when it carries information beyond `message`
(for example, `share_sum_mismatch` includes the offending totals).
Timestamps are ISO 8601 with a `Z` suffix.

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
    -H "Authorization: Bearer $A_TOK" -d '{"username":"bob"}' \
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

## Design notes

- **Immutable ledger.** Confirmed expenses and payments are never edited
  or deleted; corrections are reversing entries that reference the
  original via `reverses_*_id`. Enforcement is application-layer in v1;
  DB triggers come in a later hardening pass.
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
