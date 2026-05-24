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

Phase 5 complete on the backend. On top of the Phase 1/2/3 auth +
ledger + balance + hardening work and the Phase 4 onboarding +
pagination + auth-lifecycle pass, every relationship now carries a
required three-letter `currency_code` set at invite time and
immutable thereafter (DB-level CHECK plus an extended immutability
trigger). The currency context is per-relationship; there is no
cross-currency math anywhere in the backend. The Android client is
still a placeholder; deferred items across all phases are tracked in
[`ROADMAP.md`](ROADMAP.md).
