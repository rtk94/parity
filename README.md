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

Phase 2 complete on the backend: auth, health, relationships, expenses,
payments, and balance computation are all wired up and exercised end to
end. Remaining backend work is hardening (DB-level immutability
triggers, rate limiting, etc.); the Android client is still a Phase 5+
placeholder.
