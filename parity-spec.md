# Parity: Complete Application Specification

This document is a self-contained specification for the Parity application as designed and implemented through Phase 6. A capable model with this document should be able to reproduce the entire application with the same architectural decisions, conventions, and behaviors.

---

## 1. Project Identity and Naming

The project is called **Parity**. The name carries three intentional meanings:
- Mathematical parity: evenness, divisibility into two equal parts
- Currency parity: equal monetary value between two units
- Parity of treatment: fairness, equal standing between participants

Naming conventions applied consistently everywhere:
- Human-readable name: **Parity**
- Directory and DB filename stem: `parity`
- Pip-installable package name: `parity-backend`
- Inner Python package directory: `app/`
- Android application ID and namespace: `com.rknepp.parity`

**Key design invariants that permeate the entire codebase:**
1. **Immutable ledger.** Confirmed entries are never edited or deleted. Corrections use reversing entries. DB-level triggers serve as a hardening pass.
2. **Two-party confirmation.** Every expense/payment starts `pending`. It does not affect any balance until the counterparty confirms it.
3. **N-party-capable schema, two-party UX.** The schema uses a shares table that could support groups, but v1 flows and UI are strictly two-party.
4. **Integer cents only.** Money is stored and transmitted as integer cents. No floats anywhere, ever.
5. **UTC in the database.** All timestamps use `DateTime(timezone=True)` with `func.now()` server defaults. Localization is a client concern.

---

## 2. Directory Layouts

### 2.1 Backend (Python/Flask)
```text
backend/
├── pyproject.toml, alembic.ini, .env.example
├── instance/                    (gitignored; holds parity.db)
├── migrations/versions/         (Alembic migrations)
├── app/
│   ├── __init__.py              (create_app() factory)
│   ├── config.py                (Config, DevelopmentConfig, TestingConfig, ProductionConfig)
│   ├── extensions.py            (db, migrate, limiter instances)
│   ├── errors.py                (JSON error handlers for 400/401/403/404/409/422/429/500)
│   ├── models/                  (user.py, relationship.py, expense.py, payment.py, auth_token.py)
│   ├── auth/                    (tokens.py for hashing/expiry, decorators.py for @login_required)
│   ├── api/                     (health.py, auth_routes.py, relationships.py, expenses.py, payments.py)
│   └── services/                (business logic: relationships.py, expenses.py, payments.py, balance.py)
└── tests/                       (test_health.py, test_auth.py, test_models.py, test_relationships.py, etc.)
```

### 2.2 Android (Kotlin/Compose)
```text
android/app/src/main/java/com/rknepp/parity/
├── ParityApplication.kt, ServiceLocator.kt
├── app/                         (MainActivity.kt, ParityApp.kt)
├── auth/
│   ├── data/                    (AuthApi.kt, AuthRepository.kt, dto/)
│   ├── ui/                      (connect/, login/, register/ screens + viewmodels)
│   └── events/                  (AuthEventBus.kt)
├── home/                        (data/MeRepository.kt, ui/HomeScreen.kt, model/UserSummary.kt)
├── network/                     (ApiResult.kt, ApiError.kt, AuthInterceptor.kt, TokenAuthenticator.kt)
├── storage/                     (ServerUrlStore.kt, SecureTokenStore.kt, TinkAeadProvider.kt)
└── navigation/                  (Route.kt, ParityNavHost.kt)
```

---

## 3. Backend Stack & Data Models

**Stack:** Python 3.12+, Flask, SQLAlchemy 2.0 (typed declarative), Flask-SQLAlchemy, Flask-Migrate (Alembic), SQLite (`instance/parity.db`), Pytest, Ruff, argon2-cffi, Flask-Limiter. No Poetry/uv (uses `pyproject.toml`).
**Token generation:** `secrets.token_urlsafe(32)`. Storage: SHA-256 hex hash of the raw token. Raw token returned exactly once.

### 3.1 Data Models (SQLAlchemy 2.0)
- **User:** `id`(PK), `username`(str64, UQ), `password_hash`(str255), `display_name`(str128), `created_at`.
- **Relationship:** `id`(PK), `inviting_user_id`(FK), `invited_user_id`(FK), `status`('pending','accepted','rejected', default 'pending'), `currency_code`(str3), `created_at`.
  - **Constraints:** CHECK `inviting_user_id != invited_user_id`, CHECK `currency_code GLOB '[A-Z][A-Z][A-Z]'`, UNIQUE `(inviting_user_id, invited_user_id)`. Partial unique index ensures max 1 non-rejected relationship per user pair regardless of direction.
- **Expense:** `id`(PK), `payer_user_id`(FK), `relationship_id`(FK), `total_cents`(>0), `description`(str512), `created_by_user_id`(FK), `created_at`, `status`('pending','confirmed','discarded'), `confirmed_at/by`, `discarded_at/by`, `rejection_reason`(str512), `reverses_expense_id`(FK).
  - **Constraints:** `confirmed_by_user_id IS NULL OR confirmed_by_user_id != created_by_user_id`.
- **ExpenseShare:** `id`(PK), `expense_id`(FK), `user_id`(FK), `amount_cents`(>0). UNIQUE `(expense_id, user_id)`. Application-layer enforces share sum == total_cents.
- **Payment:** `id`(PK), `from_user_id`(FK), `to_user_id`(FK), `relationship_id`(FK), `amount_cents`(>0), `description`(str512), `created_by_user_id`(FK), `created_at`, `status`, `confirmed_at/by`, `discarded_at/by`, `rejection_reason`, `reverses_payment_id`(FK).
  - **Constraints:** `from_user_id != to_user_id`; `confirmed_by_user_id != created_by_user_id`.
- **AuthToken:** `id`(PK), `user_id`(FK), `token_hash`(str64, UQ, INDEXED), `created_at`, `last_used_at`, `revoked_at`, `expires_at`.

### 3.2 Configuration & Rate Limiting
- **Config classes:** `Config`, `DevelopmentConfig`, `TestingConfig` (disables rate limits, uses `:memory:` DB), `ProductionConfig`.
- **Rate Limits (Flask-Limiter):** Login IP (5/m), Login Username (20/h), Register (5/h), Write POSTs (60/m), Change Password (5/h), Refresh (10/h).
- **Decorators:** Outer to inner: `@route` -> `@login_required` -> `@limiter.limit`.

### 3.3 Immutability Triggers (SQLite)
BEFORE UPDATE/DELETE triggers enforce invariants directly in the database:
- **No Delete:** `expense`, `expense_share`, `payment`, `relationship`.
- **No Update Terminal:** `OLD.status != 'pending'` blocks all updates on expense, payment, relationship.
- **Immutable Columns:** Blocks updates to `payer_user_id`, `amount_cents`, `relationship_id`, `created_by`, `currency_code` even on pending rows. Firing triggers raise `IntegrityError`.

---

## 4. Backend API Contract (v1)

### 4.1 Envelopes & Pagination
- **Error Shape:** `{"error": {"code": "...", "message": "...", "details": {...}}}`
- **List Shape:** `{"items": [...], "total": 123, "limit": 50, "offset": 0, "has_more": true}`
- **Pagination:** Accepts `limit` (max 200, default 50) and `offset`. Validation errors return 422 `invalid_pagination`.
- **Timestamps:** ISO 8601 with `Z`.

### 4.2 Authentication Endpoints
- **Expiry:** Absolute (max 365d) and Idle (max 30d). Invalid/expired token -> 401 `token_expired`.
- **Timing Equalization:** `_DUMMY_HASH = _PH.hash("parity_timing_equalizer...")`. Unknown usernames verify against this to match wall-clock time of wrong-password branches.
- `POST /api/v1/auth/register`: -> 201. Error: 409 `username_taken`.
- `POST /api/v1/auth/login`: -> 200 `{token, user}`. Error: 401 `invalid_credentials`.
- `POST /api/v1/auth/logout`: -> 204. Sets `revoked_at = now()`.
- `GET /api/v1/auth/me`: -> 200 user.
- `POST /api/v1/auth/change-password`: -> 204. Updates hash, revokes all other tokens for user.
- `POST /api/v1/auth/refresh`: -> 200. Issues new token, revokes old token.

### 4.3 Relationship Endpoints
- **Authorization:** Endpoints scoped to a relationship return 404 `not_found` if caller is not a party (prevents leaking existence).
- `POST /api/v1/relationships`: `{username, currency_code, first_expense?}`. Errors: 422 `invalid_currency_code`, 404 `user_not_found`, 422 `cannot_invite_self`, 409 `relationship_exists`.
  - **Shape:** `{"id": 1, "inviting_user": {...}, "invited_user": {...}, "status": "pending", "currency_code": "USD", "created_at": "..."}`
- `GET /api/v1/relationships`: Scoped list.
- `POST /api/v1/relationships/{id}/accept`: 200. Error: 409 `relationship_not_pending`.
- `POST /api/v1/relationships/{id}/reject`: 200. Sets rejected. **Cascade:** In the same transaction, sets `status='discarded'` on all pending expenses for this relationship.
- `GET /api/v1/relationships/{id}/balance`: Requires `status='accepted'`. 
  - **Shape:** `{"relationship_id": 1, "confirmed": {"net_cents": 2500, "from_user_id": 7, "to_user_id": 5}, "projected": {...}}`
  - **Algorithm:** Uses deterministic sign convention (larger ID owes smaller ID internally). Iterates expenses/payments twice (`include_pending=False` for confirmed, `True` for projected). Discarded ignored. Reversals *not* skipped; original and reversal both contribute, naturally canceling each other out.

### 4.4 Expense / Payment Endpoints
- Create requires `relationship.status == 'accepted'`.
- **Expense Shape:** `{"id": 42, "relationship_id": 1, "payer_user_id": 5, "total_cents": 5000, "shares": [{"user_id": 5, "amount_cents": 2500}, {"user_id": 7, "amount_cents": 2500}], "status": "pending"...}`
- `POST /api/v1/expenses/{id}/confirm`: Caller must not be `created_by_user_id` (403 `cannot_self_confirm`). Error: 409 `expense_not_pending`.
- `POST /api/v1/expenses/{id}/discard`: Either party. Sets discarded.
- `POST /api/v1/expenses/{id}/reverse`: Original must be confirmed (409 `original_not_confirmed`), not a reversal, not already reversed (409 `already_reversed`). Creates new pending mirroring the original with `reverses_expense_id` set -> 201.

---

## 5. Android Client

**Stack:** Kotlin 2.1.x, AGP 8.x, JDK 17, Compose UI/Nav, Material 3, Retrofit 2 + OkHttp 4 + kotlinx.serialization, Preferences DataStore, Google Tink (AEAD via Android Keystore), ViewModel+StateFlow, Manual DI via `ParityApplication`, JUnit 4, MockWebServer.

### 5.1 Persistence & Security
- `android:allowBackup="false"` (Tink keyset is device-local). Cleartext traffic permitted for self-hosted LAN IPs.
- **ServerUrlStore:** DataStore `parity_config`. Key `server_url`.
- **SecureTokenStore:** DataStore `parity_secure`. Key: Base64 Tink-encrypted token. First access auto-generates Android Keystore master key. Decryption failure yields null.
- **TinkAeadProvider:** Interface that abstracts Keystore lookup for purely JVM unit testing (`SecureTokenStoreTest` uses a pure JVM fake).

### 5.2 Networking & DI
- **ServiceLocator:** Initialized in Application. Holds Retrofit instance, rebuilds it when server URL changes. Accessible in Compose via `LocalServiceLocator.current`. ViewModels constructed via `viewModelFactory { ... }`.
- **AuthInterceptor:** Synchronously reads token on worker thread. Appends `Authorization: Bearer <token>` if present and no header exists.
- **TokenAuthenticator:** OkHttp Authenticator for 401s. Synchronizes on a class-level lock to prevent concurrent refreshes. 
  1. Checks if another thread refreshed token. If so, retries.
  2. If not, calls `POST /api/v1/auth/refresh`.
  3. Success -> store new token, retry. Failure -> emit `AuthEvent.SessionExpired` to `AuthEventBus`, clear local token, return null.
- **ApiResult:** Sealed interface: `Success(data)`, `HttpFailure(code, ApiError?)`, `NetworkFailure(IOException)`, `UnexpectedFailure(Throwable)`. Repositories map Retrofit responses and *never* throw exceptions.

### 5.3 Screens & Navigation (MVVM)
- **StartupGate:** Combines Datastores. Emits `StartupDestination` enum: `Connect` (no URL) -> `Login` (no token) -> `Home` (has URL+token).
- **Navigation:** Type-safe Compose Nav (`Route` interface). Observing `SessionExpired` clears back stack, routes to `Login`, shows one-shot banner.
- **ConnectToServerScreen:** Validates URL parsing/schema, hits health endpoint, stores URL.
- **LoginScreen:** Calls `/login`. Handles expired-session banner. Submit -> Home. Back button from here exits app.
- **RegisterScreen:** Submit -> returns to Login with username prefilled.
- **HomeScreen:** Calls `/me` on load. Logout completes local storage clearing even if network call fails, ensuring offline user is never trapped in the app.

---

## 6. Testing Strategy & Roadmap

### 6.1 Test Coverage
- **Backend:** 
  - `test_auth.py`: register/login/me/logout flow; bad password; timing equalization mock tests; duplicate username; change-password (other-session revocation); refresh.
  - `test_relationships.py`: CRUD, bundled invite rollback, cascade discard on reject, currency validation, pagination.
  - `test_balance.py`: zero net cases, pending projected entries, confirmed reversals canceling each other out.
  - `test_triggers.py`: ORM models bypassed via raw `db.session.execute(text(...))` to trigger `IntegrityError` on illegal UPDATE/DELETE paths.
  - `test_rate_limit.py`: IP limits on register, Username limits on login, cross-user write isolation, `Retry-After` header verification.
- **Android:** 
  - `AuthRepositoryTest`: register/login/logout/verify.
  - `TokenAuthenticatorTest`: Single 401 triggers refresh, concurrent 401s yield exactly one refresh, refresh failure cascade.
  - `SecureTokenStoreTest`: JVM unit test using fake Aead.
  - `ApiResultTest`: HTTP, Network, and Unexpected failure mappings.

### 6.2 Roadmap
- **Backend:** Multi-currency per pair (allow multiple accepted relationships between same pair by currency), password reset, audit logging, tags, attachments, recurring expenses.
- **Android:** Offline support (read-only cache + queued writes), Push notifications.
- **Out of Scope forever:** OAuth, JWTs, DB-level share sum enforcement, reversing a reversal.

---

## 7. Architectural Rationale

- **SQLite:** Zero-ops for a personal self-hosted two-user tool.
- **Integer Cents:** Floating-point math introduces rounding bugs with money.
- **Opaque Tokens:** Trivial to revoke server-side compared to JWTs, which require blocklists. Two expiry windows (absolute and idle) mitigate stolen tokens.
- **Reversals vs Edits:** Immutability ensures ledger history is fully traceable. Reversals natively cancel out in the balance algorithm without needing complex conditional skipping logic.
- **Cascade Discard:** Rejecting a relationship inherently invalidates its pending expenses. Atomically discarding them avoids orphaned states.
- **Local Logout Completion:** If a user logs out offline, blocking local token deletion traps them in the app. A stale backend row is a safer failure mode than local soft-locking.
- **Manual DI:** App scale doesn't justify Hilt/Koin annotation processing overhead. A single `ServiceLocator` accessed via CompositionLocals is perfectly sufficient.
- **AuthLock in Authenticator:** Prevents parallel 401s from invalidating each other's refresh tokens by flooding the backend.
- **TinkAeadProvider Interface:** Essential dependency inversion seam. Tink's `AndroidKeystoreKmsClient` fails on the JVM test runner. Abstracting it allows `SecureTokenStoreTest` to run purely on the JVM with a mock encryption provider.
