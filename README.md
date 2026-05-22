# Parity

**Parity** is a self-hosted, two-party expense and payment tracking ledger.
The name carries three layered meanings, all relevant to what the app does:

- **Mathematical parity** — evenness, the property of being divisible into two equal parts.
- **Currency parity** — equal monetary value between two units.
- **Parity of treatment** — fairness, equal standing between participants.

The app is built around an immutable ledger and explicit two-party
confirmation: every expense or payment is pending until the counterparty
confirms it, and corrections happen via reversing entries rather than edits
or deletes.

## Repository layout

```
parity/
├── backend/   # Flask REST API (Python 3.12+)
└── android/   # Android (Kotlin) client — placeholder, Phase 5+
```

See [`backend/README.md`](backend/README.md) for setup, running the server,
testing, and the example `curl` flow.

## Status

Phase 4 complete on the backend. On top of the Phase 1/2/3 auth +
ledger + balance + hardening work, the backend now supports a bundled
invite + first-expense flow (the two rows land in one transaction;
rejecting a relationship cascade-discards any pending expenses still
attached), offset/limit pagination on `GET /relationships`,
`GET /expenses`, and `GET /payments`, a `POST /auth/change-password`
endpoint (calling session survives, other sessions revoked), and a
token lifecycle pass: every `auth_token` row carries an `expires_at`
absolute cap, the application also enforces a sliding idle window,
and a new `POST /auth/refresh` endpoint swaps an active token for a
fresh one. The Android client is still a Phase 5+ placeholder.
