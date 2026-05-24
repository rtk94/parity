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

### Bundled invite + first payment

Parallel to the Phase 4 bundled invite + first expense flow. Allow a
single `POST /relationships` request to establish a relationship and
submit an initial payment in one transaction. Deferred from Phase 2
and Phase 4.

### Password reset / forgot-password flow

Currently a forgotten password is unrecoverable without server-side DB
access. A reset flow would require an email or recovery channel that
does not exist yet. Deferred from Phase 4.

### Periodic cleanup of expired and revoked auth_token rows

The `@login_required` lookup filters out expired and revoked tokens,
so they are harmless functionally, but the table grows monotonically.
A maintenance command (or scheduled job) could purge rows whose
`revoked_at` or `expires_at` is older than some retention window.
Deferred from Phase 4.

### Audit logging

Beyond the timestamps already captured by the ledger model
(`created_at`, `confirmed_at`, `discarded_at`, and the various
`*_by_user_id` columns), there is no log of who-did-what-when at the
request level. A structured audit log would help forensics if anything
goes wrong. Deferred from Phase 3.

### Categories or tags on expenses

Free-form or controlled-vocabulary categorization of expenses, useful
for reporting. Deferred from Phase 2.

### Recurring expenses

Templates that auto-create pending expenses on a schedule. Deferred
from Phase 2.

### Attachments on expenses

Receipt photos or other supporting files attached to an expense.
Deferred from Phase 2.

### Comments on ledger entries

Free-text comments on confirmed or pending entries, distinct from the
immutable `description` field. Deferred from Phase 2.

## Android

### Offline support

Read-only browsing of cached data when offline; queued writes that
sync on reconnect. The Phase 6 plan is online-only with friendly "no
connection" states. Deferred from Phase 6 planning.

### Push notifications

"The counterparty confirmed your expense" and similar push events.
Requires FCM or a self-hosted equivalent. Deferred from Phase 2 OOS
and Phase 6 planning.

## Cross-cutting

### Cursor-based pagination

Offset/limit pagination is sufficient at the scale of a two-party
personal ledger. If the dataset ever grows past the point where deep
offsets become slow, cursor-based pagination is the upgrade path.
Deferred from Phase 4.
