# CLAUDE.md

Project-level guidance for Claude Code working in this repository.

## Project

Parity is a **centrally-hosted** two-party expense and payment tracking
service (one official hosted instance holding user accounts and data),
aimed at becoming a real public product. The backend is Flask +
SQLAlchemy 2.0 + SQLite. The Android client under `android/` is a full
Kotlin + Jetpack Compose app (auth, the ledger UI, and the "Paper"
design-system overhaul; see the Phase status below).

**Read [`docs/VISION.md`](docs/VISION.md) before any decision touching
deployment, data, or third-party dependencies** — it is the source of
truth for the product's vision and values. Note in particular that
"self-hosted" is legacy framing from the early vision (self-hosting is
now a possible *future fork*, not a mainline constraint); some docs
still carry it and are being reconciled.

Design notes, the full endpoint surface, and the example curl flow
live in `README.md` and `backend/README.md`. Read those before
proposing significant changes — the project has strong opinions
(immutable ledger, two-party confirmation, integer cents only, UTC
in DB) that should not be casually broken.

## Phase status

- Phase 1 ✓: backend skeleton (auth, health, ORM schema).
- Phase 2 ✓: relationships, expenses, payments, balance.
- Phase 3 ✓: hardening — DB-level immutability triggers, rate
  limiting, login response-timing equalisation.
- Phase 4 ✓: bundled invite + first expense, list-endpoint
  pagination, password change, token expiry (idle + absolute) and
  refresh.
- Phase 5 ✓: per-relationship currency (fixed, immutable
  `currency_code` set at invite time).
- Phase 6 ✓: Android client skeleton + auth (Kotlin + Compose;
  see `android/`).
- Backend, post-Phase 5: bundled invite + first *payment* (the
  payment-flavoured parallel of the Phase 4 first-expense flow);
  reject now cascade-discards pending payments as well as expenses.
- Phase 7 ✓: Android ledger UI on top of the auth skeleton (relationships, expenses, payments, balances).
- Phase 8 ✓: Android UI overhaul — branded Material 3 theme
  (light/dark), dashboard home tab (per-currency net position, invite
  nudges), richer relationship list/detail (avatars, status chips,
  dated ledger rows, confirm dialogs for destructive actions, error
  snackbars), settings with profile editing + password change, and
  bug fixes (stale lists on back-navigation, settings logout not
  revoking/clearing the token, float money math in expense splits,
  one-frame session-expired banner).
- Post-Phase 8, system administration: `user.is_admin` +
  `flask create-admin` (operator-only, prints a generated access key
  once), admin API (`/admin/stats`, `/admin/cleanup-tokens`,
  `/admin/reset-ledger` with confirmation phrase), `flask
  reset-ledger --yes-i-mean-it` CLI, an admin panel in the Android
  Settings screen (visible only to the admin account), and a
  migration repairing the two relationship immutability triggers
  silently dropped by the Phase 5 table recreate (plus a regression
  test that runs the real migration chain).
- Post-Phase 8, "Paper" design system: a full Android restyle
  replacing the Phase 8 Material 3 teal theme. Ink-on-warm-paper
  palette with a single forest-green accent, an amber channel reserved
  for pending (needs-a-party) state, and red for you-owe/destructive;
  Spectral serif for titles + money figures, Hanken Grotesk for UI
  (both bundled offline under `res/font/`, no Google Play Services
  fetch); pill-shaped controls and a flat, editorial layout (all-caps
  section labels, hairline dividers, no card elevation). Every screen
  was rebuilt in Paper (home, relationship ledger/detail, add-expense,
  log-payment, people list + pull-to-refresh, login/register,
  settings), plus a Compose-drawn `ParityLogo` mark and
  transparent-fill ink-outline initials avatars. Follow-up fixes:
  mapped the remaining Material 3 tonal-container roles to Paper tones
  (the FAB, currency chips, nav bar, and snackbars were falling back
  to baseline lavender) and stopped the Settings profile placeholder
  flashing before load.
- Post-Phase 8, account data export + deletion (backend): a
  GDPR-style `GET /me/export` (machine-readable JSON dump of the
  caller's user record, relationships, expenses, payments, and
  authored comments) and password-confirmed `DELETE /me`. Deletion is
  **anonymization, not hard delete** — the owner's ledger rows are part
  of the counterparty's financial record, so the row is retained but
  renamed to "Deleted user"/`deleted_user_<id>`, its password hash
  replaced with a random value, `is_admin` cleared, `deleted_at` set,
  and all tokens revoked; login and `login_required` reject deleted
  accounts (login keeps the constant-time dummy-verify), and the freed
  username is reusable. Adds a nullable `user.deleted_at` column
  (native SQLite `ADD COLUMN` migration) and `User.is_deleted`. The
  Android Settings screen exposes both (a "Download my data" export and
  a password-confirmed account deletion).
- Post-Phase 8, push notifications (FCM — see
  `docs/adr/0001-push-notification-transport.md`): a `device_token`
  model with `/auth/devices` register/unregister endpoints; a
  best-effort, transport-agnostic sender that pushes on the two
  core-loop events (a new pending entry → the counterparty; a
  confirmation → the creator) for expenses and payments, gated on
  `FCM_CREDENTIALS_FILE` (a no-op sender when unset); and an Android FCM
  client — `firebase-messaging`, a per-build-type environment split
  (release→production, debug→staging, each pairing `BASE_URL` with its
  own `google-services.json`), `POST_NOTIFICATIONS` handling, token
  register-on-login / unregister-on-logout, and notification-tap
  deep-linking. Real `google-services.json` files are gitignored (a
  committed placeholder keeps CI/fresh-clone builds working). Go-live
  steps are in `docs/DEPLOYMENT.md`.
- Post-Phase 8, push-notification catalogue extension (backend):
  broadened the sender beyond the two core-loop events to cover the
  remaining two-party ledger dispatches — a discard → the other party
  (whose pending entry is gone), a reversal → the counterparty who must
  confirm the new reversing entry, and a relationship invite → the
  invited user. New `notify_*` functions in
  `app/services/notifications.py` wired into the expense/payment
  `discard`/`reverse` services and `relationships.invite_by_username`
  (both commit branches); the bundled invite + first-entry flow fires
  only the invite (the entry isn't actionable until the relationship is
  accepted). Data payloads follow the existing `<kind>_<state>` deep-link
  shape (`expense_discarded`, `payment_reversed`, `relationship_invite`,
  …).
- Phase 9+ (planned): remaining roadmap items (offline, etc.).

Update this section as phases land.

## Environment

- Working tree: `/home/rich/Programming/parity/` (this directory).
- Remote: `git@github.com:rtk94/parity.git`, default branch `main`.
- Python: system Python 3.13 (host); `pyproject.toml` declares 3.12+.
- Backend installed editable: `pip install -e ".[dev]"` from
  `backend/`. Re-run after any change that moves the working tree.
- Required env: `SECRET_KEY` (any string for dev). For local commands
  you can prefix `SECRET_KEY=test`; for an actual server, copy
  `backend/.env.example` to `backend/.env` and fill in a real value.

## Architecture conventions

- **Routes are thin.** A blueprint route parses input, calls a service
  function, and serializes the result. No business logic in routes.
- **Services own validation and mutation.** They live in
  `backend/app/services/`. They raise structured `ServiceError`
  subclasses (`BadRequestError`, `NotFoundError`, `ConflictError`,
  `ForbiddenError`, `ValidationError`); the `translates_service_errors`
  route decorator maps them to JSON error responses.
- **Error envelope is fixed.** Always go through `app.errors.error_response`
  or raise a `ServiceError` — never `flask.abort()` or a raw `jsonify`.
  Single resources return the object directly; lists wrap in
  `{"items": [...]}`; errors wrap in
  `{"error": {"code": "...", "message": "...", "details": {}}}`.
- **Authorization first, then service.** Routes that scope to a
  relationship or entry should resolve the row and check the caller is
  a party before touching anything. Non-party access returns 404
  (`not_found`), not 403, to avoid leaking existence.
- **Money is integer cents.** Never floats. Sum integer cents and
  compare integer cents.
- **Timestamps are UTC in the DB** (`DateTime(timezone=True)`) and
  serialized with a `Z` suffix (`api/_serializers.iso8601_z`). Don't
  emit `+00:00`.
- **Immutable ledger.** Confirmed expenses and payments are never
  edited or deleted. Corrections are reversing entries that reference
  the original via `reverses_*_id`. Reversals of reversals are
  rejected (`original_is_reversal`).
- **Two-party confirmation.** Entries start `pending` and do not
  affect the confirmed balance until the counterparty confirms.
  Pending entries appear in the `projected` balance view.
- **N-party-capable schema, two-party UX.** Shares live in their own
  table so future N-party support is possible; current flows assume
  exactly two parties per relationship.

## Migration conventions

Migrations live in `backend/migrations/versions/`. Flask-Migrate +
Alembic generate them; SQLite is the target dialect.

- **Always test new migrations against a fresh DB.** Generated SQL can
  silently drop constraints. Run `flask db upgrade` on an empty
  `instance/parity.db`, then inspect the resulting schema with
  `SELECT type, name, sql FROM sqlite_schema WHERE tbl_name = '<table>'`
  to confirm what actually landed.
- **`op.batch_alter_table` is required for column renames and
  constraint changes** on SQLite (it does a move-and-copy). Pass
  `copy_from=<sa.Table>` with the *source* shape if the table has
  named CHECK constraints — reflection loses those.
- **`batch_op.create_unique_constraint` does not reliably end up in
  the recreated table** on SQLite in our setup. Use
  `op.create_index(..., unique=True)` outside the `batch_alter_table`
  block instead; SQLite treats a unique index as equivalent to a
  unique constraint.
- **Partial expression indexes need raw SQL.** Example, the symmetric
  uniqueness on `relationship`:

  ```sql
  CREATE UNIQUE INDEX uq_relationship_user_pair
    ON relationship (
      MIN(inviting_user_id, invited_user_id),
      MAX(inviting_user_id, invited_user_id)
    )
    WHERE status != 'rejected';
  ```

  This is the *sole* symmetric-uniqueness mechanism between two users.
  Don't add a redundant unconditional `UNIQUE(inviting, invited)`; it
  would block re-invites after rejection.
- **`sa.Enum` on SQLite is just `VARCHAR` with no CHECK.** Enum value
  validation happens at the SQLAlchemy layer only. If you need
  DB-level enforcement, add a CHECK explicitly.

## Testing conventions

- Run with `SECRET_KEY=test pytest` from `backend/`. Tests use an
  in-memory SQLite DB and a fresh schema per test.
- `pyproject.toml` sets `filterwarnings = ["error"]`, so any warning
  fails the suite. The `conftest.py` `app` fixture calls
  `db.engine.dispose()` in teardown to close pooled sqlite connections
  — required on Python 3.13 to avoid `ResourceWarning` exit-time noise.
- **Factories drive the HTTP API, not the ORM.** Add helpers to
  `tests/factories.py` (`make_user`, `make_logged_in_user`,
  `make_relationship`, `make_expense`, `make_payment`). They double as
  smoke tests for the routes they touch.
- Cover the happy path, every error code, and authorization (non-party
  returns 404, self-confirm returns 403, etc.). Existing test files in
  `backend/tests/` show the pattern.
- All three must pass before commit:

  ```bash
  cd backend
  SECRET_KEY=test pytest
  ruff check .
  ruff format --check .
  ```

## Git workflow

This repo lives on `main` plus short-lived topic branches. **Never push
directly to `main`** — always go through a PR.

### 1. Start from a clean main

```bash
cd /home/rich/Programming/parity
git checkout main
git pull --ff-only
git status              # working tree clean before branching
```

### 2. Verify the starting point

Before branching, confirm `HEAD` is aligned with `origin/main`. Step 1
guarantees this when followed in order, but it's easy to skip — e.g.
when work began on a previous feature branch that has since been
merged but is still checked out locally. The check below catches that
case without depending on memory:

```bash
git fetch origin
git log origin/main..HEAD --oneline   # must print nothing
```

If the output is non-empty, `HEAD` carries commits that are not in
`origin/main`. Branching here would bundle those commits into the new
PR. Resolve before continuing — typically by checking out `main` and
re-running step 1, or by stashing in-flight work and replaying it on a
branch cut from `main`.

### 3. Branch

```bash
git checkout -b <branch-name>
```

Naming convention (lowercase, hyphenated):

- `phase-N` for phase work (`phase-3`, `phase-4`, …)
- `feature/<slug>` for new features outside a phase
- `fix/<slug>` for bug fixes
- `chore/<slug>` for tooling, docs, cleanup

### 4. Code, test, lint

Iterate locally. Before committing, all three must pass cleanly with no
warnings:

```bash
cd backend
SECRET_KEY=test pytest
ruff check .
ruff format --check .
```

### 5. Commit

Match the established commit style: title `<topic>: <one-line summary>`,
body in 2–4 paragraphs that explain *what* changed and *why*. Phase
commits are titled `Phase N: <summary>`. Do **not** add any agent
signature or attribution — no `Co-Authored-By` trailer, no "Generated
with …" line, no tool/model footer. Commits and PRs carry no
authorship attribution to any AI agent.

Use a heredoc so multi-line formatting survives the shell:

```bash
git commit -m "$(cat <<'EOF'
Phase N: <summary>

<paragraph 1: what / why>

<paragraph 2: notable design choices, gotchas, test coverage>
EOF
)"
```

### 6. Push

```bash
git push -u origin <branch-name>
```

### 7. Open a PR

```bash
gh pr create --base main --title "<title>" --body "$(cat <<'EOF'
## Summary
- <3–5 bullets covering what this PR does and any design choices>

## Test plan
- [x] <checks you ran — pytest, ruff, manual curl flow, etc.>
EOF
)"
```

Title should match the commit title.

`gh` is authenticated on this host as `rtk94` via a stored personal
access token. If `gh` calls start failing on auth (token expired or
revoked), regenerate at https://github.com/settings/tokens with scopes
`repo` and `read:org`, then:

```bash
echo 'ghp_NEW_TOKEN' | gh auth login --hostname github.com --with-token
```

### 8. After the PR is merged

```bash
git checkout main
git pull --ff-only
git branch -d <branch-name>             # local cleanup
git push origin --delete <branch-name>  # remote cleanup, if not auto-deleted
```

## Don't

- Don't push to `main` directly.
- Don't commit `.env`, `instance/parity.db`, caches, or anything else
  covered by `.gitignore` (check `git status` carefully before staging).
- Don't amend or force-push a branch once a PR is open without
  surfacing it to the user first.
- Don't paste tokens or other secrets into commit messages, PR bodies,
  or conversation. If you see a token in conversation, remind the user
  to rotate it.
- Don't add features, error handling, validation, or abstractions
  beyond what the current task requires.
- Don't break a design invariant (immutability, two-party
  confirmation, integer cents, UTC, etc.) without surfacing it
  explicitly to the user first.
