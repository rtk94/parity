# Parity Project Context

Parity is a self-hosted, two-party expense and payment tracking ledger designed around an **immutable ledger** and **explicit two-party confirmation**. Corrections are made via reversing entries rather than edits or deletions.

## Project Overview

- **Purpose:** Fair and transparent expense/payment tracking between two parties.
- **Architecture:**
    - **Backend:** Flask REST API (Python 3.12+) serving an immutable SQLite ledger.
    - **Android:** Kotlin + Jetpack Compose mobile client.
- **Core Principles:**
    - **Immutability:** Confirmed entries are never edited or deleted; corrected via `reverses_*_id`.
    - **Two-Party Confirmation:** Entries remain `pending` and do not affect balance until confirmed by the counterparty.
    - **Currency:** Each relationship has a fixed, immutable currency code set at invite time.
    - **Financial Integrity:** Money is stored and handled as integer cents.

## Backend (Python/Flask)

Located in the `backend/` directory.

### Technologies
- **Framework:** Flask
- **Database:** SQLite with SQLAlchemy ORM.
- **Migrations:** Flask-Migrate (Alembic).
- **Security:** Password hashing, token-based auth (idle + absolute expiry), rate limiting (Flask-Limiter).
- **Integrity:** SQLite triggers as a DB-level backstop for ledger immutability.
- **Tooling:** `ruff` for linting and formatting, `pytest` for testing.

### Key Commands
- **Setup:** `pip install -e ".[dev]"`
- **Database Migration:** `flask db upgrade`
- **Run Server:** `flask run`
- **Run Tests:** `pytest`
- **Lint/Format:** `ruff check .` / `ruff format .`

### API Conventions
- All endpoints live under `/api/v1`.
- Authentication via `Bearer` token.
- Standard response envelope for lists and errors.
- Timestamps are UTC ISO 8601 with `Z` suffix.

---

## Android (Kotlin/Compose)

Located in the `android/` directory.

### Technologies
- **UI:** Jetpack Compose with Material 3.
- **Navigation:** Type-safe Compose Navigation.
- **Networking:** Retrofit 2 + OkHttp 4 + kotlinx.serialization.
- **Concurrency:** Coroutines + StateFlow.
- **Storage:** Preferences DataStore (URL) and Google Tink (Encrypted tokens via Android Keystore).
- **DI:** Manual dependency injection via `ServiceLocator`.
- **Testing:** JUnit 4, MockWebServer, Turbine.

### Key Commands
- **Build:** `./gradlew assembleDebug`
- **Run Tests:** `./gradlew test`
- **Run Lint:** `./gradlew lint`
- **Install:** `./gradlew installDebug`

### Architectural Patterns
- **Service Locator:** `ParityApplication` maintains the global `ServiceLocator`.
- **Token Management:** `TokenAuthenticator` handles transparent 401 recovery and token refresh.
- **Secure Storage:** `SecureTokenStore` ensures tokens are always encrypted on disk.
- **Navigation:** Centralized `ParityNavHost` handles routing and session-expiry cascades.

---

## Development Conventions

- **Ledger Invariants:** Never bypass service-layer validation for expenses and payments.
- **Testing:** Always include unit tests for new logic. Backend tests use an in-memory DB; Android tests use MockWebServer.
- **Error Handling:** Use the project's standard error codes and envelopes.
- **Security:** Do not log tokens or passwords. Maintain `allowBackup="false"` on Android to protect Keystore-backed keys.
- **Linting:** Ensure code passes `ruff` (backend) and `gradlew lint` (Android) before completion.
