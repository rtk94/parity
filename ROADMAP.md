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

### Multi-currency per pair — shipped

Multiple simultaneous accepted relationships between the same user
pair, distinguished by currency. The Phase 2 partial unique index was
relaxed from `(min_user, max_user) WHERE status != 'rejected'` to
include `currency_code` (migration `6cdaa625dc51_multi_currency_index`).
Landed in commit `acf09d5`; closes issue #5.

### Categories or tags on expenses — shipped

Free-form categorization of expenses via a nullable `expense.category`
column (migration `b65303d06d02_add_category_to_expense`), validated in
`app/services/expenses.py`. Landed in commit `f7f49ba`; closes issue
#10.

### Audit logging — shipped

Request-level who-did-what-when log via `app/services/audit.py` and the
`audit_log` table (migration `ba4efb8a9c2b_add_audit_log_table`). Landed
in commit `acf09d5`; closes issue #9.

### Comments on ledger entries — shipped

Free-text comments on expenses and payments (distinct from the
immutable `description`) via `app/services/comments.py`, create/list
endpoints on both entry types, and the `comment` table (migration
`2748f2e00746_add_comment_table`). Landed in commit `f7f49ba`; closes
issue #13.

### Password reset / forgot-password flow

Currently a forgotten password is unrecoverable without server-side DB
access. A reset flow would require an email or recovery channel that
does not exist yet. Deferred from Phase 4. (Issue #7.)

### Recurring expenses (Issue #11)

Templates that auto-create pending expenses on a schedule. Deferred
from Phase 2.

### Attachments on expenses

Receipt photos or other supporting files attached to an expense.
Deferred from Phase 2. (Issue #12.)

## Android

### Offline support

Read-only browsing of cached data when offline; queued writes that
sync on reconnect. The Phase 6 plan is online-only with friendly "no
connection" states. Deferred from Phase 6 planning. (Issue #14.)

### Push notifications — shipped

Delivered via FCM across backend (device registration + a best-effort
sender) and the Android client; see
[ADR-0001](docs/adr/0001-push-notification-transport.md) and the
phase-status log in `CLAUDE.md`. The catalogue covers the two core-loop
events (new pending entry, confirmation) plus discards, reversals, and
relationship invites — every backend push dispatch a two-party ledger
naturally produces. Any further events would ride the same `notify_*`
dispatch pattern in `app/services/notifications.py`.

## Cross-cutting

### Cursor-based pagination

Offset/limit pagination is sufficient at the scale of a two-party
personal ledger. If the dataset ever grows past the point where deep
offsets become slow, cursor-based pagination is the upgrade path.
Deferred from Phase 4. (Issue #16.)
