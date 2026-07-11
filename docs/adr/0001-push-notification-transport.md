# ADR-0001: Push notification transport

**Status:** Accepted
**Date:** 2026-07-10
**Deciders:** Repository owner (@rtk94)

> **Note — recommendation revised during review.** This ADR was first
> drafted recommending *UnifiedPush + polling*, on the premise that
> Parity is a self-hosted, Google-free tool. During review that premise
> was retired: Parity's mainline is now a **centrally-hosted public
> product**, and "no Google Play Services" was recognised as an artefact
> of the old self-hosted vision rather than a core value (see
> [VISION.md](../VISION.md)). With that constraint lifted, the decision
> flips to **FCM**. The full four-option analysis is retained below
> because the reasoning — and the conditions under which a different
> answer would win — remains useful.

## Context

Parity's core loop is two-party confirmation: one party logs an expense
or payment, it sits `pending`, and it does not move the confirmed
balance until the counterparty confirms it. The loop has a discovery
gap — the counterparty has no way to learn that something is waiting
except by opening the app. Push notifications ("Alex logged a $40
expense", "Sam confirmed your payment") close that gap and are the
highest-leverage addition to the product's core value.

This ADR decides **how notifications reach the device**. It does not
decide the notification catalogue, copy, or deep-link targets — those
follow once the transport is fixed.

### Forces at play

- **Centrally-hosted public product.** Parity is operated as a single
  official hosted service (see [VISION.md](../VISION.md)). There is one
  operator, so a transport that needs one cloud project (e.g. a single
  Firebase project) carries **no per-deployment burden** — the earlier
  objection that "every self-hoster would need their own Firebase
  project" no longer applies.
- **A hard Google dependency is acceptable on the mainline.** The
  no-Google-Play-Services stance was a self-hosted-era artefact, not a
  core value. It no longer rules options out.
- **Synchronous backend, no broker.** The backend runs as gunicorn
  synchronous workers behind nginx over SQLite, with no message queue or
  background-worker infrastructure. Long-lived connections (WebSockets)
  do not fit synchronous workers; an **outbound HTTP POST fired during
  the request** that flips an entry to `pending` fits with zero new
  infrastructure.
- **Reliability matters for a public product.** Real users depending on
  the service raise the bar on delivery reliability and battery
  behaviour.
- **Android target levels.** `targetSdk = 36` makes the Android 13+
  `POST_NOTIFICATIONS` runtime permission mandatory regardless of
  transport. `minSdk = 28` is compatible with every option below.

## Decision

**Adopt Firebase Cloud Messaging (FCM) as Parity's push transport.**

Rationale: with the self-hosted constraint retired, FCM is the standard,
most reliable Android push path, and its costs now fall away. The single
Firebase project is owned by the one central service — no per-deployment
burden — and the backend send is a simple best-effort outbound POST that
fits the synchronous gunicorn/SQLite backend with no new infrastructure.
FCM gives the best delivery and battery behaviour, including waking a
killed app, which a public product wants. UnifiedPush and WorkManager
polling are documented below as alternatives, and remain the likely
choices for a future **self-hosted fork** whose values would again
exclude a Google dependency.

## Options Considered

### Option A: Firebase Cloud Messaging (FCM) — **chosen**

The standard, "expected" Android push path.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low client, low backend (outbound POST to FCM) |
| Cost | Free tier ample |
| Reliability | Excellent — best-in-class delivery & battery |
| Ethos fit (central) | **Good** — one operator, one Firebase project |
| Killed-app delivery | Yes |

**Pros:**
- Most reliable delivery and best battery behaviour; wakes a killed app.
- Well-documented; what most Android developers reach for first.
- Backend send is a simple outbound POST — fits the sync backend with no
  new infrastructure.
- One Firebase project owned by the central service — the per-deployment
  burden that would exist for self-hosters does not apply here.

**Cons:**
- Requires Google Play Services on the device and a `google-services.json`
  in the app — a Google dependency. Acceptable on the central mainline;
  disqualifying for a future self-hosted fork.
- Notification metadata transits Google's infrastructure.

### Option B: UnifiedPush (e.g. ntfy distributor)

An open push standard: the device runs a *distributor* app that holds
the connection; apps register through it for an **endpoint URL**; the
backend POSTs to that URL.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium client, low backend |
| Cost | Free; optional self-hosted distributor |
| Reliability | Good — depends on the distributor |
| Ethos fit (central) | Neutral — Google-free but adds user friction |
| Killed-app delivery | Yes, via the distributor |

**Pros:**
- Google-free and self-hostable end to end — the natural pick for a
  future self-hosted fork.
- Backend side is identical in shape to FCM (outbound POST to a stored
  endpoint).

**Cons:**
- The user must install a distributor app and, for full independence,
  self-host it — onboarding friction that a public product should not
  impose on ordinary users.
- Smaller ecosystem; reliability depends on the chosen distributor.

### Option C: Persistent WebSocket / long-poll

The app holds an open connection to the backend, which streams events.

| Dimension | Assessment |
|-----------|------------|
| Complexity | High client + **high backend** |
| Reliability | Poor for background/killed-app delivery |
| Backend fit | **Poor** — mismatched with sync workers |
| Killed-app delivery | No (without a heavy foreground service) |

**Pros:**
- Fully self-contained; lowest latency while foregrounded.

**Cons:**
- Mismatched with the synchronous gunicorn backend; would need an
  ASGI/async server or a separate long-lived service.
- No killed-app delivery without a battery-hostile foreground service.
- Highest engineering/operational cost for the weakest background
  guarantees.

### Option D: WorkManager periodic polling

No push: a `WorkManager` job periodically hits the REST API for a
pending count and raises a local notification on change.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low client, **zero** backend |
| Reliability | Deterministic but delayed (≥15-min floor) |
| Backend fit | Excellent — reuses existing REST |
| Killed-app delivery | N/A (scheduled wake, not push) |

**Pros:**
- Simplest possible; no new backend work, no third party.
- Reuses the existing authenticated REST surface.

**Cons:**
- Not real-time — a 15-minute floor, deferred further under Doze.
- Not true push; a stopgap rather than a destination.

## Trade-off Analysis

Retiring the self-hosted constraint collapses what used to be the
decisive trade-off. Previously *ethos* (no Google) ruled FCM out and
*backend fit* ruled WebSocket out, leaving UnifiedPush and polling. Now:

- **FCM's only real cost — the Google dependency and per-deployment
  Firebase burden — no longer bites.** There is one operator and one
  Firebase project, and a Google dependency is acceptable on the
  mainline.
- **Backend fit** still eliminates WebSocket/long-poll: the synchronous
  gunicorn/SQLite backend has no async surface, and streaming would be a
  disproportionate change that still fails the killed-app case.
- **Polling** remains a viable *stopgap* (it needs no backend work) but
  is not the destination for a public product that wants prompt,
  reliable delivery.

FCM therefore wins on the dimension a public product cares about most —
reliable delivery, including to a killed app — while its costs now fall
away. UnifiedPush stays the documented answer for a future self-hosted
fork; keep the backend send behind a small transport-agnostic interface
so that fork (or a change of heart) is cheap.

## Consequences

**Easier:**
- The core two-party loop gains prompt, reliable discovery.
- Backend push is a small best-effort outbound POST — no broker, no async
  rewrite.
- A device-registration model is reusable groundwork if offline sync is
  tackled later.

**Harder / new surface:**
- A Firebase project for the central service, with server credentials
  held by the backend and `google-services.json` in the app build.
- A new **device registration** concept on the backend: a table of
  per-user FCM registration tokens, register/unregister endpoints, and a
  send hook on the `pending` (and likely `confirm`) transitions. Treat
  registration tokens like secrets — never log; revoke on logout.
- `POST_NOTIFICATIONS` runtime-permission handling on Android 13+.
- The send in the request path must be **best-effort / fire-and-forget** —
  a slow or failing FCM call must never block or fail the ledger write.
- A Google Play Services dependency now ships in the app (a change from
  the previously GMS-free client).

**To revisit:**
- Keep the backend send behind a transport-agnostic interface so a future
  self-hosted fork can swap FCM for UnifiedPush, and so polling can serve
  as an interim, without reworking the call sites.

## Action Items

1. [x] Owner sign-off on the transport decision — **FCM** (2026-07-10).
2. [ ] Create the Firebase project for the central service; add
   `google-services.json` to the app and server credentials to the
   backend config.
3. [ ] Design the backend device-registration schema (per-user FCM
   tokens) and register/unregister endpoints, following the
   immutable-ledger and error-envelope conventions.
4. [ ] Add a best-effort send hook on the `pending`/`confirm` transitions
   behind a transport-agnostic interface (FCM implementation first).
5. [ ] Integrate the FCM client on Android; handle `POST_NOTIFICATIONS`,
   register the token on login, revoke on logout.
6. [ ] Deep-link notifications to the relevant relationship/entry screen.
7. [ ] Update `ROADMAP.md` (the push-notifications entry) and the CLAUDE.md
   phase-status log once the work lands.
