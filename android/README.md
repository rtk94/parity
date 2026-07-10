# Parity Android Client

The Android client for **Parity** — a self-hosted, two-party expense
and payment tracking ledger. The backend lives at `../backend`; see
[`../backend/README.md`](../backend/README.md) for setup.

Through **Phase 8**, the client carries the project skeleton,
authentication, the core business-logic screens (relationships,
expenses, payments, balances, comments), a dashboard home tab with
per-currency net positions, settings with profile editing and password
change, and confirmation dialogs around every destructive ledger
action.

On top of that, the **"Paper" design-system overhaul** restyles the
entire app, replacing the Phase 8 Material 3 teal theme. Paper is an
ink-on-warm-paper palette with a single forest-green accent, an amber
channel reserved for pending (needs-a-party) state, and red for
you-owe/destructive actions. Titles and money figures use the Spectral
serif; all other UI uses Hanken Grotesk — both bundled offline under
`res/font/` (no Google Play Services fetch). Controls are pill-shaped
and screens use a flat, editorial layout (all-caps section labels,
hairline dividers, no card elevation) with transparent-fill
ink-outline avatars, in full light/dark.

## Stack

- Kotlin 2.1, Android Gradle Plugin 8.7, Gradle 8.10
- JDK 17
- Min SDK 28 (Android 9), target / compile SDK 36
- Jetpack Compose with Material 3 (Compose BOM-coordinated)
- Compose Navigation with `@Serializable` type-safe routes
- Retrofit 2 + OkHttp 4 + kotlinx.serialization
- Coroutines + StateFlow; ViewModel-per-screen
- Preferences DataStore backing the encrypted token store
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

- `relationships/ui/RelationshipListViewModelTest` and
  `RelationshipDetailViewModelTest` — state machines, counterparty
  resolution, and caller-perspective balance mapping.
- `relationships/ui/MoneyFormatTest` — integer-cents money formatting,
  grouping, and signed deltas.
- `ledger/ui/AmountAndSplitTest` — decimal-input parsing and the
  integer-only split math (shares always sum to the total).
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

No lint suppressions are wired in. If any baseline file appears under
`app/lint-baseline.xml` it should be reviewed and removed — the
project does not ship a baseline.

## Install on a device / emulator

```bash
./gradlew installDebug
```

Then launch **Parity** from the launcher.

## End-to-end flow against a running backend

The server URL is compiled in via the `BASE_URL` build config field in
`app/build.gradle.kts`; point it at your instance (for an emulator
against a local backend, `http://10.0.2.2:5000/` plus a cleartext
network-security exception) and rebuild.

1. Run the backend (`cd ../backend && SECRET_KEY=test flask run`).
2. From the sign-in screen, tap **Create an account**, fill in the
   three fields, and submit. On success the app returns to **Sign
   in** with the username pre-filled.
3. Submit the credentials. On success the app lands on the **Home**
   dashboard: greeting, per-currency net position, pending-invite
   nudges, and a preview of your most active relationships.
4. From the **Relationships** tab, invite a second account, accept
   the invite from the other side, and add expenses/payments from the
   relationship detail screen. Pending entries must be confirmed by
   the counterparty; discard/reverse/decline actions ask for
   confirmation first.
5. From **Settings**, edit the display name, change the password, or
   tap **Log out** (with confirmation). Logout revokes the server
   token and clears secure storage before returning to sign-in.

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

See [`../ROADMAP.md`](../ROADMAP.md). Notable items still deferred:

- Editing the server URL in-app (it is a compile-time constant).
- Biometric authentication.
- Compose UI tests beyond the instrumentation E2E flow.
- Offline support, push notifications.
- ktlint / detekt / Spotless and CI for the Android side.
