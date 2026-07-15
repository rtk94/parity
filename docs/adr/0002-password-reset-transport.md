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
