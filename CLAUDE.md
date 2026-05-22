# CLAUDE.md

Project-level guidance for Claude Code working in this repository.

## Project

Parity is a self-hosted two-party expense and payment tracking ledger.
The backend is Flask + SQLAlchemy 2.0 + SQLite. The Android client
under `android/` is a Phase 5+ placeholder.

Design notes, the full endpoint surface, and the example curl flow
live in `README.md` and `backend/README.md`. Read those before
proposing significant changes — the project has strong opinions
(immutable ledger, two-party confirmation, integer cents only, UTC
in DB) that should not be casually broken.

## Phase status

- Phase 1 ✓: backend skeleton (auth, health, ORM schema).
- Phase 2 ✓: relationships, expenses, payments, balance.
- Phase 3 (planned): hardening — DB-level immutability triggers, rate
  limiting, etc.
- Phase 5+ (planned): Android client.

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

### 2. Branch

```bash
git checkout -b <branch-name>
```

Naming convention (lowercase, hyphenated):

- `phase-N` for phase work (`phase-3`, `phase-4`, …)
- `feature/<slug>` for new features outside a phase
- `fix/<slug>` for bug fixes
- `chore/<slug>` for tooling, docs, cleanup

### 3. Code, test, lint

Iterate locally. Before committing, all three must pass cleanly with no
warnings:

```bash
cd backend
SECRET_KEY=test pytest
ruff check .
ruff format --check .
```

### 4. Commit

Match the established commit style: title `<topic>: <one-line summary>`,
body in 2–4 paragraphs that explain *what* changed and *why*. Phase
commits are titled `Phase N: <summary>`. Append the Co-Authored-By
trailer for the agent that did the work.

Use a heredoc so multi-line formatting survives the shell:

```bash
git commit -m "$(cat <<'EOF'
Phase N: <summary>

<paragraph 1: what / why>

<paragraph 2: notable design choices, gotchas, test coverage>

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### 5. Push

```bash
git push -u origin <branch-name>
```

### 6. Open a PR

```bash
gh pr create --base main --title "<title>" --body "$(cat <<'EOF'
## Summary
- <3–5 bullets covering what this PR does and any design choices>

## Test plan
- [x] <checks you ran — pytest, ruff, manual curl flow, etc.>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
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

### 7. After the PR is merged

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
