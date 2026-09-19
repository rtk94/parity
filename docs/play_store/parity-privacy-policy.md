# Privacy Policy for Parity

**Last updated:** [PLACEHOLDER: effective date]

## Who we are

Parity ("Parity", "we", "us", "our") is a small, independently operated
service for tracking shared expenses and payments between two connected
users. It consists of an Android app and a backend API hosted on Oracle
Cloud Infrastructure (OCI).

The data controller responsible for your personal data is:

> [PLACEHOLDER: data controller legal name or entity]

For any privacy question, or to exercise the rights described below,
contact us at:

> [PLACEHOLDER: contact for privacy/data requests, email]

## Who this policy applies to

> [PLACEHOLDER: geographic scope, GDPR-reasonable regardless of user
> location, OR explicitly scoped to US users only]

This policy is written to meet a general (GDPR-style) standard of data
protection for all users. If you scope the service to US users only, the
GDPR-specific sections (legal basis, and some of the rights below) can be
removed; see the notes flagged at the end.

## What we collect

### Information you provide

- **Account information:** a username, a display name, and a password.
  Your password is never stored in readable form. It is stored only as an
  argon2 hash, which cannot be reversed back into your password.
- **Ledger data:** the expenses and payments you record, including
  amounts, descriptions, timestamps, and which of the two connected users
  owes whom.
- **Connection data:** records that link your account to the other user
  you share a ledger with, including the invitation and confirmation
  status of that connection and the currency you have chosen for it.

### Information created when you use the service

- **Authentication data:** session tokens issued when you log in. On our
  server these are stored only as hashes, never in readable form. The
  Android app stores your active session token encrypted on your device.
- **Technical data:** your device's IP address is processed transiently to
  enforce rate limits and to detect and prevent abuse of the service.

### What we do not collect

- No bank account, card, or other payment-instrument data. Parity does not
  process payments, hold funds, or move money. It only records what users
  tell it.
- No location data, contacts, advertising identifiers, or biometric data.
- No analytics, crash-reporting, or advertising SDKs. We do not track your
  activity across other apps or websites.

## Why we collect it

We use the data above only to operate the service:

- To create and maintain your account and authenticate you.
- To let you and your connected user record, confirm, and view shared
  expenses and payments, and to compute balances between you.
- To keep the service secure, including enforcing rate limits and
  preventing abuse.

We do not use your data for advertising, profiling, or any purpose
unrelated to running Parity.

## Legal basis for processing (GDPR)

Where GDPR applies, we rely on the following legal bases:

- **Performance of a contract:** processing your account information,
  ledger data, and connection data is necessary to provide the service you
  have signed up for.
- **Legitimate interests:** processing authentication and technical data
  (including your IP address) to secure the service, enforce rate limits,
  and prevent abuse. We consider this proportionate and limited to what is
  necessary for those purposes.

If you scope the service to US users only, this section can be removed.

## Sharing your data

We do not sell your data, and we do not share it with third parties for
their own purposes.

Your data is stored on Oracle Cloud Infrastructure, which provides the
hosting for our backend. OCI processes the data solely to provide that
hosting on our instructions; it does not use your data for its own
purposes. Other than this hosting arrangement, your data is not disclosed
to anyone except the other user you have chosen to share a ledger with, and
except where we are legally required to disclose it.

## How long we keep your data

> [PLACEHOLDER: retention, indefinite until deletion requested, OR a fixed
> period]

Session tokens are retained until they expire or are revoked (for example,
when you log out).

## Security

We take reasonable measures to protect your data, including:

- Passwords are stored only as argon2 hashes, never in readable form.
- Session tokens are stored on the server only as hashes, never in readable
  form.
- All traffic between the app and the server is encrypted in transit using
  HTTPS/TLS.
- On your device, the Android app stores your active session token
  encrypted, using a key held in the device's hardware-backed keystore.

No system is perfectly secure, and we cannot guarantee absolute security,
but we work to protect your data using the measures above.

## Your rights

Depending on where you live, you may have the following rights over your
personal data:

- **Access:** request a copy of the personal data we hold about you.
- **Correction:** ask us to correct inaccurate account information.
- **Deletion:** ask us to delete your account and associated personal data.
- **Portability:** request your data in a portable format (where GDPR
  applies).
- **Objection and restriction:** object to, or ask us to restrict, certain
  processing (where GDPR applies).

A note on the ledger's design: Parity's expense and payment records are
append-only by design. Recorded entries are not edited or deleted in normal
use; instead, a correction is made by recording a reversing entry that
cancels out the original. This preserves an accurate, tamper-evident
history for both users. Correcting the record of a specific transaction
therefore happens through reversing entries within the app. Deleting your
account and personal data is handled separately, as described below.

## How to exercise your rights

To exercise any of the rights above, contact us at the email listed in the
"Who we are" section.

**Account and data deletion:**

> [PLACEHOLDER: deletion process, manual (user emails a request and we
> delete by hand) OR self-serve endpoint. Note: a self-serve deletion
> endpoint does not currently exist in the API. Do not describe self-serve
> deletion as available unless and until it is built.]

We will respond to requests within a reasonable time and in line with any
applicable legal deadlines.

## Children's privacy

Parity is not directed at, or intended for, children. We do not knowingly
collect personal data from children under the age of 16 (or under 13 in
jurisdictions where that is the applicable threshold, such as the United
States under COPPA). If you believe a child has provided us with personal
data, contact us and we will delete it.

## Where your data is stored

Your data is stored on Oracle Cloud Infrastructure.

> [PLACEHOLDER: OCI hosting region. If the service is GDPR-scoped and data
> is stored outside the user's region, describe the transfer and its
> safeguards here. If US-only, this can be a brief statement of the hosting
> location.]

## Changes to this policy

We may update this policy from time to time. When we do, we will change the
"Last updated" date at the top. Significant changes will be communicated
through the app or by other reasonable means. Continuing to use the service
after a change means you accept the updated policy.

## Contact

For any questions about this policy or your data, contact us at:

> [PLACEHOLDER: contact for privacy/data requests, email]

---

## Placeholders to fill in before publishing

1. **Effective date** at the top of the document.
2. **Data controller legal name or entity** (Who we are).
3. **Contact email** for privacy/data requests (appears twice: Who we are,
   and Contact).
4. **Geographic scope** (Who this policy applies to). This decision also
   governs whether the GDPR "Legal basis" section stays or goes, and which
   children's age threshold applies.
5. **Retention** period or "indefinite until deletion requested" (How long
   we keep your data).
6. **Deletion process**, manual vs self-serve. Self-serve deletion does not
   exist in the API yet; do not describe it as available until built.
7. **OCI hosting region** and, if GDPR-scoped with cross-border storage,
   the transfer safeguards (Where your data is stored).

## Things to confirm or decide (not placeholders, but review these)

- **At-rest encryption not claimed.** The Security section deliberately does
  not claim the OCI database or its storage is encrypted at rest, because
  that was not confirmed. If your OCI block volume / DB storage is
  encrypted and you want to state it, add that to the Security section.
- **Server / proxy log retention.** The policy notes that IP addresses are
  processed transiently for rate limiting. If your OCI host, reverse proxy,
  or web server persists access logs containing IP addresses, that is a
  retained data category and should be disclosed and given a retention
  period. Confirm your logging configuration.
- **US state privacy laws.** If you scope US-only, GDPR framing can be
  dropped, but state laws (for example California's CCPA/CPRA) may impose
  their own disclosure and rights requirements depending on your user base
  and revenue. Evaluate whether any apply before relying on a US-only,
  GDPR-stripped version.
- **Connection data and the counterparty.** The policy states that ledger
  and connection data are visible to the other user you share a ledger
  with. Confirm this matches the product behavior you intend to describe.
