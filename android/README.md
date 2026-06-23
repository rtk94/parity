# Parity Android Client

The Android client for **Parity** — a self-hosted, two-party expense
and payment tracking ledger. The backend lives at `../backend`; see
[`../backend/README.md`](../backend/README.md) for setup.

Through **Phase 7**, the client carries the project skeleton, authentication,
and core business-logic screens: relationships, expenses, payments, and balances.
Settings and profile editing arrive in Phase 8.

## Stack

- Kotlin 2.1, Android Gradle Plugin 8.7, Gradle 8.10
- JDK 17
- Min SDK 28 (Android 9), target / compile SDK 36
- Jetpack Compose with Material 3 (Compose BOM-coordinated)
- Compose Navigation with `@Serializable` type-safe routes
- Retrofit 2 + OkHttp 4 + kotlinx.serialization
- Coroutines + StateFlow; ViewModel-per-screen
- Preferences DataStore for the server URL
- Google Tink AEAD (keyset wrapped by an Android Keystore master
  AEAD) for the bearer token
- Manual DI via an `Application` subclass acting as a service locator
- JUnit 4 + MockWebServer + Turbine + kotlinx-coroutines-test for
  unit tests

## Prerequisites

- JDK 17 on `PATH` (or set `JAVA_HOME` to a JDK 17 install).
- Android Studio (Ladybug or newer) with:
  - Android SDK Platform **36** installed
  - Android SDK Build-Tools 36.x
  - A device or emulator running API 28 or higher
- The first Gradle build downloads dependencies and the Android SDK
  components Android Studio doesn't already have. A working internet
  connection is required for that first build.

Set `local.properties` (Android Studio writes this automatically, or
create it manually) to point at your Android SDK:

```
sdk.dir=/path/to/Android/Sdk
```

## Build

```bash
cd android
./gradlew assembleDebug
```

## Run unit tests

```bash
./gradlew test
```

The test source set covers:

- `network/ApiResultTest` — Retrofit response → `ApiResult` mapping.
- `network/TokenAuthenticatorTest` — 401 recovery, single refresh
  under concurrent 401s, terminal failure cascade.
- `auth/AuthRepositoryTest` — login / register / logout via
  MockWebServer, including the "logout clears the local token even on
  network failure" invariant.
- `storage/SecureTokenStoreTest` — AEAD round-trip, clear, corruption
  handling. The test substitutes a JVM-backed Tink keyset for the
  Android Keystore one via the `TinkAeadProvider` abstraction, so the
  test runs on a plain JVM with no Android Keystore.

## Run lint

```bash
./gradlew lint
```

No lint suppressions are wired in for Phase 7. If any baseline file
appears under `app/lint-baseline.xml` it should be reviewed and
removed — Phase 7 does not ship a baseline.

## Install on a device / emulator

```bash
./gradlew installDebug
```

Then launch **Parity** from the launcher.

## End-to-end flow against a running backend

1. Run the backend (`cd ../backend && SECRET_KEY=test flask run`).
   If you're using an emulator, the emulator can reach the host at
   `http://10.0.2.2:5000`. On a physical device on the same LAN,
   use the host's LAN IP.
2. Open the app. The first screen is **Connect to server**.
   - A malformed URL surfaces an inline validation error.
   - A reachable URL that responds to `GET /api/v1/health` is
     persisted, and the app routes to **Sign in**.
3. From the sign-in screen, tap **Create an account**, fill in the
   three fields, and submit. On success the app returns to **Sign
   in** with the username pre-filled.
4. Submit the credentials. On success the app routes to **Home**
   and shows the display name fetched from `GET /api/v1/auth/me`.
5. Tap **Log out**. The app returns to sign-in and the bearer token
   is cleared from secure storage.
6. Kill the backend, then tap log out before restarting it. The
   local session still clears; the UI surfaces "Server logout
   failed; local session cleared".

## Forced-logout cascade (manual)

1. Log in via the app.
2. On the backend host:
   ```bash
   sqlite3 backend/instance/parity.db \
     "UPDATE auth_token SET expires_at = '1970-01-01 00:00:00+00:00' WHERE revoked_at IS NULL;"
   ```
3. Foreground the app and trigger any authenticated request (a
   relaunch of the home screen is sufficient).
4. The `/me` request 401s. The token authenticator attempts a
   refresh; the refresh itself 401s on an expired token. The
   `AuthEventBus` emits `SessionExpired`; the nav host routes back
   to **Sign in** with a banner.

## Architecture notes

- **Networking lives in `network/`.** `RetrofitFactory` builds the
  shared `OkHttpClient` (with `AuthInterceptor` and
  `TokenAuthenticator`) and exposes the `Retrofit` builder. The
  `ServiceLocator` rebuilds Retrofit whenever the persisted server
  URL changes.
- **`TokenAuthenticator` is the only path that refreshes a token.**
  Concurrent 401s synchronize on a class-level lock so only one
  refresh call goes out per stale token. A failure on the refresh
  call (or a 401 on the refresh endpoint itself) clears the local
  token and emits `AuthEvent.SessionExpired`; the nav host listens
  and routes back to sign-in.
- **`SecureTokenStore` never writes plaintext.** The token is
  encrypted with a Tink AES-GCM key; the keyset is itself wrapped
  by an Android Keystore master AEAD, so the on-disk keyset is
  unusable without the device's hardware-backed key. A `TinkAeadProvider`
  seam lets JVM tests substitute a non-keystore AEAD.
- **`allowBackup="false"` is deliberate.** Auto-backup or device
  transfer of a Keystore-backed keyset can corrupt the keyset on the
  destination device and lock the user out. The user can re-pair
  with the server after a restore; that is the simplest correct
  behavior. A Phase 9 follow-up could add a "warn on first
  cleartext-URL connect" UX.
- **Manual DI.** `ParityApplication` constructs a single
  `ServiceLocator`. ViewModels obtain their dependencies via a
  factory that reads `LocalServiceLocator.current`. No DI framework
  is wired in.

## Deferred to later phases

See [`../ROADMAP.md`](../ROADMAP.md). Notable items already deferred
from Phase 7:

- Editing the server URL after initial setup (Phase 9 settings).
- Password change UI (Phase 9).
- Biometric authentication.
- Compose UI tests.
- Offline support, push notifications.
- ktlint / detekt / Spotless and CI for the Android side.
