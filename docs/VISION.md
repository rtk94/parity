# Parity — Vision & Values

**Status:** Authoritative
**Last updated:** 2026-07-10

This document is the source of truth for **what Parity is** and the
**values that guide its decisions**. When an architecture or product
call is ambiguous — especially one that touches deployment, data, or
third-party dependencies — this document decides it. Where older docs
still describe Parity as a "self-hosted" tool, they are legacy framing
(see [§6](#6-legacy-framing-being-reconciled)); this document wins.

---

## 1. What Parity is

Parity is a **centrally-hosted, two-party expense and payment tracking
service**, aimed at becoming a **real public product**.

There is **one official hosted instance**, operated by the maintainer,
that holds user accounts and their ledger data. Users sign up for that
service; they do not run their own copy. The product is built around an
immutable ledger and explicit two-party confirmation: every expense or
payment is pending until the counterparty confirms it, and corrections
happen via reversing entries rather than edits or deletes.

The name **Parity** carries three intentional meanings, all still
central to the product: mathematical parity (evenness), currency parity
(equal value), and parity of treatment (fairness, equal standing
between the two participants).

## 2. How we got here

Parity **began as a self-hosted, personal, two-user tool.** That framing
was load-bearing in the early architecture: SQLite was chosen as
"zero-ops for a personal self-hosted two-user tool," the server URL is
compiled into the client with no connect screen, cleartext traffic is
permitted for LAN IPs, and the Paper design system bundles its fonts
offline specifically to avoid a Google Play Services fetch.

Over time the **reality moved to a hosted service** — production and
staging run on a VPS behind nginx with managed TLS and real domains
(see [DEPLOYMENT.md](DEPLOYMENT.md)) — while the *narrative* in the docs
lagged behind, still calling Parity "self-hosted."

On **2026-07-10** the vision was made explicit: **Parity's mainline is a
centrally-hosted public product.** Self-hosting is no longer a design
constraint on the mainline; it is a *possible future fork* (see
[§5](#5-deployment-philosophy-the-part-that-changed)). This document
records that decision so future readers understand why the pivot
happened and don't re-litigate settled ground.

## 3. Enduring values

These are the values that define Parity. They are **independent of how
it is deployed** and did not change in the pivot. Treat them as
inviolable unless this document is deliberately revised.

1. **Fairness & parity.** Two-party confirmation, equal standing between
   participants. Neither party can unilaterally move the shared balance;
   an entry affects it only once the *other* party confirms. This is the
   heart of the product, not merely a feature.
2. **An immutable, auditable ledger.** Confirmed entries are never
   edited or deleted. Corrections are reversing entries that reference
   the original. History is always fully traceable. DB-level triggers
   harden this.
3. **Financial integrity.** Money is integer cents, everywhere, always —
   never floats. Timestamps are UTC in the database. Correctness of the
   ledger outranks convenience.
4. **Custodianship & privacy.** *(Elevated by the pivot.)* Because Parity
   now **holds users' data on their behalf**, protecting and respecting
   that data is a first-class value, not an afterthought. Collect the
   minimum necessary; secure it in transit and at rest; make it
   **portable and deletable** by the user. The account data-export and
   deletion features are therefore *core*, not optional niceties.
5. **Trust & security.** Opaque, server-revocable tokens with idle +
   absolute expiry; timing-equalised auth to resist username
   enumeration; encrypted client-side token storage. Security choices
   favour being able to *revoke and reason about* access.

## 4. Ambition

Parity is being built as a **real public product**: open (or
waitlisted) sign-ups, people who don't know the maintainer using it,
and growth as a design consideration rather than an afterthought.

This ambition promotes a set of concerns to **first-class roadmap
items** — acknowledged here even though they are not all scheduled yet:

- **Datastore scaling.** SQLite was justified by "personal two-user
  tool." As a multi-tenant public service, the migration path to
  Postgres (already noted in `backend/README.md`) becomes a real
  question of durability and concurrency, not a hypothetical.
- **Backups & disaster recovery.** Holding many users' financial records
  centrally makes a real backup/restore/DR story mandatory.
- **Privacy policy & terms of service.** A public service handling
  personal financial data needs both, plus a clear data-handling stance.
- **Abuse, spam & rate control.** Open sign-ups invite abuse; rate
  limiting exists but anti-spam / anti-abuse will need attention.
- **Observability.** Logging, metrics, and error tracking appropriate to
  a service other people depend on.
- **Account lifecycle.** Export + anonymising deletion — **done**; the
  rest (e.g. password reset, which needs a recovery channel) is on the
  roadmap.

These are consequences of the ambition, recorded so they aren't
rediscovered as surprises.

## 5. Deployment philosophy (the part that changed)

**The mainline targets one central hosted service. Self-hosting is not a
current design constraint.**

Concretely, this means:

- **Do not reject or contort a decision solely because it wouldn't suit
  a self-hoster.** "But a self-hoster would have to…" is not, by itself,
  a valid objection on the mainline anymore.
- Standard, reliable, well-supported solutions appropriate to a hosted
  public service are **preferred**, even when they add a third-party or
  cloud dependency — e.g. **FCM** for push, **Postgres** for the
  datastore, **HTTPS-only** transport. The first worked example is
  [ADR-0001](adr/0001-push-notification-transport.md), which chose FCM
  once the no-Google-Play-Services constraint was recognised as a
  self-hosted artefact rather than a core value.
- **Self-hosting is a possible future fork**, with its own values (likely
  reinstating constraints like no-hard-Google-dependency and zero-ops
  datastore). That fork — if it happens — is where those trade-offs get
  made. It should not tax the mainline pre-emptively.

Note that some habits from the self-hosted era remain *good engineering*
regardless and need no change on ideological grounds — e.g. bundling
fonts offline is simply fast and reliable. Keep such things because they
are good, not because self-hosting requires them.

## 6. Legacy framing being reconciled

The word "self-hosted" (and assumptions flowing from it) still appears
in several documents written during the earlier vision. They are being
reconciled to this document; until each is updated, **this document is
authoritative**. Known locations: `README.md`, `CLAUDE.md`, `GEMINI.md`,
`docs/README.md`, `parity-spec.md`, `backend/README.md`,
`android/README.md`, and `ROADMAP.md`.

Reconciling them is intentionally incremental — the identity-defining
and agent-facing docs are updated first (they most directly steer
decisions); the deeper implementation specs follow.

## 7. What has *not* changed

To be explicit, the pivot does **not** touch:

- The ledger invariants: immutability, two-party confirmation, integer
  cents, UTC-in-DB, N-party-capable schema with two-party UX.
- The backend API contract and error-envelope conventions.
- The Paper design system.
- The architecture conventions in `CLAUDE.md` (thin routes, services own
  validation/mutation, authorization-before-service, etc.).

Only the **deployment philosophy** and the decisions **derived from it**
change. When in doubt, protect the enduring values in
[§3](#3-enduring-values) and consult [§5](#5-deployment-philosophy-the-part-that-changed)
for the deployment stance.
