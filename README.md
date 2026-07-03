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
└── android/   # Android (Kotlin + Jetpack Compose) client
```

See [`backend/README.md`](backend/README.md) for backend setup, running
the server, testing, and the example `curl` flow. See
[`android/README.md`](android/README.md) for the Android build, test,
lint, and end-to-end-flow instructions.

## Status

**Backend:** Phase 5 complete. On top of the Phase 1/2/3 auth + ledger
+ balance + hardening work and the Phase 4 onboarding + pagination +
auth-lifecycle pass, every relationship carries a required
three-letter `currency_code` set at invite time and immutable
thereafter (DB-level CHECK plus an extended immutability trigger).
The currency context is per-relationship; there is no cross-currency
math anywhere in the backend.

**Android:** Phase 8 complete. The client ships a Kotlin + Compose
single-activity app, a Retrofit/OkHttp networking layer with a
token-refresh `Authenticator` and forced-logout cascade, an encrypted
token store (Google Tink AEAD wrapped by an Android Keystore master
key), and the full ledger UI: a dashboard home tab with per-currency
net positions, relationship list/detail with avatars, status chips
and dated ledger entries, expense/payment creation with integer-cents
split math, comments, settings with profile editing and password
change, and a branded Material 3 theme with dark-mode support.

Deferred items across all phases are tracked in
[`ROADMAP.md`](ROADMAP.md).
