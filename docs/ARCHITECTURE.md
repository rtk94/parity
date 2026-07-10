# Architecture

Parity consists of a backend API and an Android mobile client, decoupled via a RESTful HTTP API.

## Backend Architecture

The backend is a Flask application designed with clean service layers, an immutable SQLite ledger, and token-based authentication.

- **Framework:** Flask (Python 3.12+)
- **Database:** SQLite, managed via SQLAlchemy ORM and Alembic migrations.
- **Security:** Token-based auth (idle + absolute expiry), rate limiting (Flask-Limiter), and password hashing.
- **Ledger Immutability:** Enforced via SQLite triggers at the database level to prevent UPDATE/DELETE operations on confirmed entries. Corrections use a `reverses_*_id` column.

### Data Models
- **User:** Represents registered users.
- **Relationship:** A connection between two users with a defined currency.
- **Expense / Payment:** The core ledger items representing costs and transfers. Includes categories and comments.
- **Comment:** Free-text discussion on specific ledger items.
- **AuthToken:** Session management.

## Android Architecture

The Android application is built entirely with Jetpack Compose using modern Android development practices.

- **UI:** Jetpack Compose (Material 3), styled with the in-house "Paper" design system — an ink-on-warm-paper palette with a single forest-green accent and an amber pending channel, Spectral serif for titles/money and Hanken Grotesk for UI (bundled offline), pill controls, and a flat editorial layout with light/dark support.
- **Architecture Pattern:** MVVM (Model-View-ViewModel) with Unidirectional Data Flow using `StateFlow`.
- **Navigation:** Compose Navigation with a central `ParityNavHost`.
- **Networking:** Retrofit 2 + OkHttp + kotlinx.serialization.
- **Storage:** Preferences DataStore for configuration, Google Tink for secure, encrypted token storage.
- **Dependency Injection:** Manual DI via a centralized `ServiceLocator` bound to the `Application` class.
