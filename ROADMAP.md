# Parity Roadmap

This document tracks features and improvements deferred for later
phases. Items listed here are "might do later" candidates, not
commitments. Items deliberately scoped out of Parity forever (OAuth,
email-based auth, SMS, refresh tokens as a separate credential type,
token rotation on every request, reversing a reversal, DB-level
share-sum enforcement, and similar) are documented inside the relevant
phase prompts and do not appear here.

Each item notes the phase that first deferred it.

## Backend

### Multi-currency per pair

Allow multiple simultaneous accepted relationships between the same
user pair, distinguished by currency. Implementation would relax the
Phase 2 partial unique index from `(min_user, max_user) WHERE status !=
'rejected'` to `(min_user, max_user, currency_code) WHERE status !=
'rejected'`. Deferred from Phase 5 planning.

### Password reset / forgot-password flow

Currently a forgotten password is unrecoverable without server-side DB
access. A reset flow would require an email or recovery channel that
does not exist yet. Deferred from Phase 4.

### Categories or tags on expenses

Free-form or controlled-vocabulary categorization of expenses, useful
for reporting. Deferred from Phase 2.

### Recurring expenses

Templates that auto-create pending expenses on a schedule. Deferred
from Phase 2.

### Attachments on expenses

Receipt photos or other supporting files attached to an expense.
Deferred from Phase 2.

## Android

### Offline support

Read-only browsing of cached data when offline; queued writes that
sync on reconnect. The Phase 6 plan is online-only with friendly "no
connection" states. Deferred from Phase 6 planning.

### Push notifications — shipped

Delivered via FCM across backend (device registration + a best-effort
sender) and the Android client; see
[ADR-0001](docs/adr/0001-push-notification-transport.md) and the
phase-status log in `CLAUDE.md`. The v1 catalogue covers the two
core-loop events (new pending entry, confirmation). Remaining follow-up:
extend the catalogue to discards, reversals, and relationship invites
(the `notify_*` dispatch functions are the extension points).

## Cross-cutting

### Cursor-based pagination

Offset/limit pagination is sufficient at the scale of a two-party
personal ledger. If the dataset ever grows past the point where deep
offsets become slow, cursor-based pagination is the upgrade path.
Deferred from Phase 4.
