# ADR-0001: Push notification transport

**Status:** Proposed
**Date:** 2026-07-10
**Deciders:** Repository owner (@rtk94)

## Context

Parity's core loop is two-party confirmation: one party logs an expense
or payment, it sits `pending`, and it does not move the confirmed
balance until the counterparty confirms it. The loop has a discovery
gap — the counterparty has no way to learn that something is waiting
for them except by opening the app. Push notifications ("Alex logged a
$40 expense", "Sam confirmed your payment") close that gap and are the
highest-leverage addition to the product's core value.

This ADR decides **how notifications reach the device**. It does not
decide the notification catalogue, copy, or deep-link targets — those
follow once the transport is fixed.

### Forces at play

Several project realities constrain the choice more than they would for
a typical app:

- **Self-hosted, Google-free by design.** Parity is a self-hosted tool.
  The "Paper" design system deliberately bundles its fonts offline
  specifically to avoid a Google Play Services fetch, and the app ships
  no Firebase/GMS dependency today. A transport that reintroduces a hard
  Google dependency cuts against the project's stated identity.
- **Synchronous backend, no broker.** The backend runs as gunicorn
  synchronous workers (2 in production, 1 in staging) behind nginx on a
  single VPS, over SQLite, with **no message queue or background-worker
  infrastructure**. Long-lived connections (WebSockets) do not fit
  synchronous workers, and there is no existing async surface or broker
  to build streaming on. An **outbound HTTP POST fired during the
  request** that flips an entry to `pending`, by contrast, fits the
  current architecture with zero new infrastructure.
- **Low event frequency.** A two-party personal ledger produces a
  handful of entries per week, not per second. Real-time latency
  measured in seconds is a nice-to-have, not a requirement; minutes is
  tolerable.
- **Android target levels.** `targetSdk = 36` means the Android 13+
  `POST_NOTIFICATIONS` runtime permission is mandatory regardless of
  transport. `minSdk = 28` is compatible with every option below.
- **Single-maintainer project.** Per-deployment setup burden and
  ongoing operational surface are real costs; simpler is materially
  better here.

## Decision

**Adopt UnifiedPush as Parity's push transport, with a WorkManager
polling fallback for devices without a distributor.** Reject FCM as the
default transport because it violates the self-hosted, Google-free
principle, and reject a persistent WebSocket/long-poll channel because
it is mismatched with the synchronous backend and does not deliver when
the app is killed.

Rationale in brief: UnifiedPush keeps the whole system Google-free and
self-hostable, and — critically — the backend side is just an outbound
POST to a per-device endpoint URL, which is the one push shape that fits
the current synchronous gunicorn/SQLite backend without new
infrastructure. Polling as a fallback means users who do not run a
distributor still get notified (just less promptly), so no user is left
without the feature, and it reuses the existing REST API with zero
backend work.

This is a **values-and-constraints call as much as a technical one**,
so it is filed as *Proposed* pending owner sign-off rather than adopted
unilaterally.

## Options Considered

### Option A: Firebase Cloud Messaging (FCM)

The standard, "expected" Android push path.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low client, low backend (outbound POST to FCM) |
| Cost | Free tier ample; cost is the *dependency*, not dollars |
| Ethos fit | **Poor** — hard Google/GMS dependency |
| Reliability | Excellent — best-in-class delivery & battery |
| Self-host burden | High — each operator needs a Firebase project |

**Pros:**
- Most reliable delivery and best battery behaviour; wakes a killed app.
- Well-documented, what most Android developers reach for first.
- Backend send is a simple outbound POST — fits the sync backend.

**Cons:**
- Requires Google Play Services on the device and a `google-services.json`
  baked into the APK — a hard Google dependency in a tool whose identity
  is self-hosted independence.
- Every self-hoster who builds their own APK needs their **own** Firebase
  project and server credentials — real per-deployment setup burden.
- Routes every notification's metadata through Google's infrastructure.

### Option B: UnifiedPush (e.g. self-hosted or public ntfy distributor)

An open push standard: the device runs a *distributor* app (such as
ntfy) that holds one connection to a push server; apps register through
it and receive an **endpoint URL**; the Parity backend POSTs to that URL
to deliver a message.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium client (library + registration), low backend |
| Cost | Free; optional self-hosted ntfy is a small extra service |
| Ethos fit | **Excellent** — Google-free, self-hostable end to end |
| Reliability | Good — depends on the chosen distributor |
| Self-host burden | Medium — user installs/points at a distributor |

**Pros:**
- Fully aligned with the self-hosted, Google-free identity; no GMS.
- Backend side is identical in shape to FCM — an outbound POST to a
  stored per-device endpoint — so it fits the synchronous backend with
  no new infrastructure.
- Operator/user can self-host the distributor (ntfy) or use a public one.

**Cons:**
- The user must install a distributor app (e.g. ntfy) and, for full
  independence, self-host it — more onboarding friction.
- Smaller ecosystem; fewer worked examples than FCM.
- Delivery reliability is only as good as the distributor the user picks.

### Option C: Persistent WebSocket / long-poll

The app holds an open connection (or repeated long-polls) to the Parity
backend, which streams events directly.

| Dimension | Assessment |
|-----------|------------|
| Complexity | High client + **high backend** |
| Cost | Higher — persistent connections, battery |
| Ethos fit | Good — fully self-contained, no third party |
| Reliability | Poor for background/killed-app delivery |
| Self-host burden | Low for the operator, high engineering cost |

**Pros:**
- Fully self-contained — no distributor, no Google, no third party.
- Lowest latency while the app is foregrounded and connected.

**Cons:**
- **Mismatched with the backend.** Synchronous gunicorn workers (2 in
  prod) cannot hold many persistent connections; this needs an ASGI/async
  server or a separate long-lived service — a large architectural change.
- Does not deliver when the app is killed without a foreground service,
  which is heavy, battery-hostile, and increasingly restricted.
- Highest ongoing engineering and operational cost for the weakest
  background guarantees.

### Option D: WorkManager periodic polling

No push at all: a `WorkManager` job periodically hits the existing REST
API for a pending count and raises a local notification on change.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low client, **zero** backend |
| Cost | Minimal |
| Ethos fit | Excellent — reuses the existing REST API |
| Reliability | Deterministic but delayed (≥15-min floor) |
| Self-host burden | None |

**Pros:**
- Simplest possible; no new backend work, no new infrastructure, no
  third party, no Google.
- Reuses the existing authenticated REST surface.
- Given the low event frequency, "within ~15–30 minutes" may be entirely
  acceptable for v1.

**Cons:**
- Not real-time — `WorkManager` enforces a 15-minute minimum interval,
  and the OS may defer further under Doze.
- Polls on a schedule regardless of activity — some wasted battery/network.
- Not true "push"; a stopgap rather than a destination.

## Trade-off Analysis

The decisive constraints are **ethos fit** and **backend fit**, and they
point the same way.

- **Ethos** eliminates FCM as the *default*. A self-hosted, deliberately
  Google-free product should not make its flagship notification path
  depend on Google Play Services and a per-operator Firebase project. (FCM
  remains reasonable as an *optional* transport an operator could opt
  into, but not as the project's chosen path.)
- **Backend fit** eliminates WebSocket/long-poll. The synchronous
  gunicorn/SQLite backend has no async surface or broker; streaming would
  be a disproportionate architectural change for a two-party ledger, and
  it still fails the killed-app case.

That leaves the two outbound/pull options — UnifiedPush and polling —
which are the two that fit both constraints. They are not really rivals:
UnifiedPush is the real-time destination; polling is the zero-dependency
floor. Adopting **UnifiedPush primary + polling fallback** means
distributor users get prompt push while everyone else still gets
notified, and the backend gains exactly one capability — POST to a stored
endpoint — that is shared groundwork either way. Polling can even ship
*first* as an interim if the UnifiedPush client integration slips,
because it needs no backend changes.

## Consequences

**Easier:**
- The core two-party loop gains timely discovery without compromising the
  self-hosted, Google-free identity.
- Backend push is a small, synchronous outbound POST — no broker, no async
  rewrite, no new runtime.
- A device-endpoint registration model is reusable groundwork if offline
  sync is tackled later.

**Harder / new surface:**
- A new **device registration** concept on the backend: a table of
  per-user UnifiedPush endpoint URLs, register/unregister endpoints, and a
  send hook fired when an entry transitions to `pending` (and likely on
  `confirm`). Endpoints are per-device secrets — treat them like tokens
  (never log, revoke on logout).
- Client onboarding now has to explain distributors to users who want
  real-time push — a documentation and UX cost.
- `POST_NOTIFICATIONS` runtime-permission handling on Android 13+.
- An outbound network call in the request path that flips an entry to
  `pending` must be **fire-and-forget / best-effort** — a slow or dead
  push endpoint must never block or fail the ledger write.

**To revisit:**
- If UnifiedPush onboarding proves too high-friction for real users,
  reconsider offering FCM as an *opt-in* transport behind the same
  backend send abstraction.
- If polling turns out to be good enough at this scale, UnifiedPush may
  be deferred indefinitely — so build the send path behind an interface
  that both can satisfy.

## Action Items

1. [ ] Owner sign-off on the transport decision (moves this ADR to
   *Accepted*).
2. [ ] Design the backend device-registration schema (per-user endpoint
   URLs) and register/unregister endpoints, following the immutable-ledger
   and error-envelope conventions.
3. [ ] Add a best-effort send hook on the `pending`/`confirm` transitions
   behind a transport-agnostic interface (UnifiedPush POST first).
4. [ ] Integrate a UnifiedPush client library on Android; handle
   `POST_NOTIFICATIONS`, register the endpoint on login, revoke on logout.
5. [ ] Deep-link notifications to the relevant relationship/entry screen.
6. [ ] Add the WorkManager polling fallback for devices without a
   distributor.
7. [ ] Document distributor setup (self-hosted ntfy and a public option)
   in `docs/`.
8. [ ] Update `ROADMAP.md` (the push-notifications entry) and the CLAUDE.md
   phase-status log once the work lands.
