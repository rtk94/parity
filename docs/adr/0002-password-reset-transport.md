# ADR-0002: Password reset & recovery channel

**Status:** Accepted
**Date:** 2026-07-14
**Deciders:** Repository owner (@rtk94)

## Context

Parity accounts are username + password only. A forgotten password is
currently unrecoverable without direct server-side DB access (issue #7).
For a **centrally-hosted public product** (see [VISION.md](../VISION.md)
§4, which lists password reset as a roadmap item that "needs a recovery
channel"), self-service account recovery is table stakes.

Two facts frame the decision:

1. **No recovery channel exists.** The `user` table stores no email,
   phone, or secondary factor. Any reset flow must first introduce one.
2. **"Email-based auth" was scoped out — as a self-hosted artefact.**
   The old ROADMAP scoped email out "forever", but that flowed from the
   retired self-hosted, zero-ops, no-external-dependency framing.
   [VISION.md](../VISION.md) §5 now explicitly **prefers** standard
   third-party/cloud dependencies for the hosted mainline (FCM,
   Postgres, HTTPS-only are the cited examples), and ADR-0001 is the
   template: recognise a constraint as a self-hosted artefact, then lift
   it.

### Forces at play

- **One operator, one hosted instance.** A single outbound-email
  configuration serves everyone; there is no per-deployment burden (the
  same reasoning that made FCM acceptable in ADR-0001).
- **Custodianship & minimal data** ([VISION.md](../VISION.md) §3.4).
  Email is additional PII. It should be **optional**, collected only
  from users who want recoverability, never shown to the counterparty,
  and included in export + cleared on account deletion.
- **Enumeration resistance** ([VISION.md](../VISION.md) §3.5). The
  request endpoint must not reveal whether an address is registered —
  mirroring the existing timing-equalised login.
- **Avoid vendor lock-in now.** Choosing a *specific* provider (SES vs
  Postmark vs …) is a deploy-time concern, not an architectural one.

## Decision

Build **email-based password reset**, with the transport kept
**provider-agnostic and config-gated**, exactly like the push sender in
ADR-0001.

1. **Email as optional account data.** Add a nullable, unique
   `user.email`. Settable at registration and via `PATCH /me`. Included
   in `to_private_dict` (self-view) and the data export; **never** in
   `to_public_dict` (the counterparty must not see it). Cleared on
   account deletion.
2. **Transport seam.** An `EmailSender` protocol with a `NullEmailSender`
   (default — logs, sends nothing) and an `SmtpEmailSender` (stdlib
   `smtplib`) selected by config. With `MAIL_SERVER` unset the sender is
   a no-op, so dev/test/any unconfigured instance behaves safely and the
   *specific* SMTP provider (SES, Postmark, a relay) is chosen purely in
   deployment config — no code or vendor SDK.
3. **Single-use hashed reset tokens.** A `password_reset_token` table
   stores only the SHA-256 hash of a high-entropy secret (never the raw
   token), with a short expiry (`PASSWORD_RESET_LIFETIME_MINUTES`,
   default 60) and a `used_at` marker. This matches how `auth_token`
   already stores only hashes.
4. **Two endpoints.**
   - `POST /auth/password-reset/request {email}` → always `204`
     (enumeration-resistant). If a live account owns that address, prior
     unused tokens are invalidated, a fresh token is minted, and an
     email carrying the raw token is sent best-effort.
   - `POST /auth/password-reset/confirm {token, new_password}` → on a
     valid, unexpired, unused token: set the new hash, mark the token
     used, and **revoke every auth token** for the user (a reset implies
     possible compromise, so all sessions end).

## Consequences

- Users who never set an email still cannot self-recover; they fall back
  to operator assistance. This is an accepted trade-off of keeping email
  optional (§3.4). A future ADR could add a second factor.
- The backend gains one optional third-party surface (an SMTP endpoint).
  Until it is configured, reset requests succeed silently and deliver
  nothing — safe to roll out staging-first, like FCM.
- Email joins the set of personal data under custodianship: it is
  exported, hidden from counterparties, and cleared on deletion.
- Enumeration resistance rests on the **identical `204` response** and
  sending nothing to unregistered addresses — not on timing. The
  registered path does more work (token insert + synchronous email
  send), so a residual timing side-channel remains; equalising it to the
  login flow's standard (or moving delivery off the request path) is a
  possible future hardening, tracked as a follow-up rather than blocking
  this ADR.

## Alternatives considered

- **Operator-assisted reset (CLI/admin only).** No new dependency, no
  PII, ships immediately — but not self-service, so it does not scale to
  a public product. Retained as the implicit fallback for email-less
  accounts.
- **Recovery codes at registration.** Self-service and no email/PII, but
  shifts the burden of storing codes onto users and helps no one who
  didn't save them. Higher UX cost for less reach than email.
- **SMS / phone.** More PII, per-message cost, and weaker security than
  email for this purpose. Rejected.

---

## Amendment (2026-09-17): email required, short code credential

**Status:** Accepted, amending the Decision section above.

### Context

The first internal-testing release put this flow in front of real
users, and it could not work for any of them. Three faults compounded:
no `MAIL_SERVER` was ever configured on the hosted instance, so the
request endpoint returned its enumeration-resistant `204` while
`NullEmailSender` dropped every message; email was optional, so most
test accounts had no address to send to; and the credential was a
43-character URL-safe token that the Android UI asked users to hand-type
into a field it already labelled a "reset code".

### Changes

1. **Email is now required**, superseding Decision 1. It is mandatory at
   registration and can no longer be cleared via `PATCH /me`. The
   Android client blocks legacy email-less accounts behind a prompt at
   login until an address is supplied.

   This **narrows the minimal-data position** in
   [VISION.md](../VISION.md) §4, and the trade is deliberate: an
   account nobody can recover is the worse custodianship outcome. Email
   remains hidden from counterparties, exported, and cleared on
   deletion, so every other commitment in that section stands.

2. **The credential is an 8-digit numeric code**, not a long token. The
   stored digest is **scoped to the owning user** (`sha256("<user_id>:<code>")`).
   That scoping is load-bearing: an unscoped short code would be
   guessable against *any* account holding a live code, turning a
   per-account guess into a pooled one. `confirm` therefore takes
   `{email, code, new_password}` and resolves the account before
   checking the code.

3. **Each code has an attempt budget** (`PASSWORD_RESET_MAX_ATTEMPTS`,
   default 5). A wrong guess increments a counter; a spent budget burns
   the code. This cap, not the per-IP rate limit, is the real guard on a
   short code — the rate limit was split so `confirm` (10/hour) is
   looser than `request` (5/hour) and a user who mistypes their own code
   is not locked out along with everyone behind their NAT.

4. **The lifetime drops from 60 to 15 minutes**, and
   `PASSWORD_RESET_URL_BASE` is removed. There is no web client, so a
   link in the email had nowhere to land.

### Consequences

- **Known weakness:** SHA-256 of an 8-digit code has only 10^8
  preimages, so an attacker holding a database dump can recover a live
  code offline. The mitigations are the 15-minute window, single use,
  and the attempt cap — and an attacker with the database can already
  rewrite `password_hash` directly, so this is not the marginal risk it
  might appear. Revisit if reset ever guards something the database
  itself does not.
- The `user.email` column stays **nullable** at the DB level: deleted
  accounts null it out as part of anonymization, so `NOT NULL` is not
  available. The requirement is enforced in the service/API layer.
- `POST /auth/password-reset/confirm` changed shape, breaking the
  already-shipped internal-testing build. That build's reset path never
  worked, so nothing functional is lost, but testers need the new APK.
- Registration now sends a **welcome email** naming the recovery
  address. It doubles as a live delivery check at the moment the address
  is typed, so a typo surfaces at signup rather than when the user is
  locked out. It is best-effort and never blocks registration.
- Delivery is still synchronous on the request thread (the SMTP timeout
  is now 5s rather than 10s). Moving it off-thread remains the open
  follow-up from the original Consequences section.
