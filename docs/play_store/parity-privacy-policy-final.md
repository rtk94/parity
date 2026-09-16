# Privacy Policy for Parity

**Last updated:** [FILL IN: date you actually publish this]

## Who we are

Parity ("Parity", "we", "us", "our") is a small, independently operated
service for tracking shared expenses and payments between two connected
users. It consists of an Android app and a backend API hosted on Oracle
Cloud Infrastructure (OCI).

The data controller responsible for your personal data is:

> Richard Knepp

For any privacy question, or to exercise the rights described below,
contact us at:

> richardtknepp@gmail.com

(This will move to a domain-hosted address, e.g. rich@rknepp.com, once
that mail service is set up. Update this document when it does.)

## Who this policy applies to

This policy is written to meet a general (GDPR-reasonable) standard of
data protection for all users, regardless of where you live.

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

## Sharing your data

We do not sell your data, and we do not share it with third parties for
their own purposes.

Your data is stored on Oracle Cloud Infrastructure (US East, Ashburn),
which provides the hosting for our backend. OCI processes the data solely
to provide that hosting on our instructions; it does not use your data for
its own purposes. Other than this hosting arrangement, your data is not
disclosed to anyone except the other user you have chosen to share a
ledger with, and except where we are legally required to disclose it.

## How long we keep your data

We keep your data indefinitely, until you request deletion of your
account. See "Account and data deletion" below for what that involves.

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
- **Portability:** request your data in a portable format.
- **Objection and restriction:** object to, or ask us to restrict, certain
  processing.

A note on the ledger's design: Parity's expense and payment records are
append-only by design. Recorded entries are not edited or deleted in normal
use; instead, a correction is made by recording a reversing entry that
cancels out the original. This preserves an accurate, tamper-evident
history for both users. Correcting the record of a specific transaction
happens through reversing entries within the app. Account deletion is
handled separately, as described below.

## How to exercise your rights

To exercise any of the rights above, contact us at richardtknepp@gmail.com.

**Account and data deletion:**

To request deletion of your account, email richardtknepp@gmail.com (or use
our account deletion page at [FILL IN: deletion page URL once hosted]) from
an address you can be reached at, including your Parity username. We will
confirm the request before acting on it.

When we process a deletion request:

- Your username, display name, and password are permanently removed and
  your account can no longer be logged into.
- Expense and payment records connected to your account are **not**
  deleted outright if you shared them with another user. Because those
  records are also part of the other person's financial history with you,
  we retain the ledger entries themselves (amounts, descriptions, dates),
  but remove your identifying information from them, replacing it with an
  anonymized placeholder. The other user's own account and data are
  unaffected.

This process is currently manual (we do not yet have a fully automated
self-serve deletion feature in the app). We aim to complete deletion
requests within 30 days of confirming them. Deletion of your identifying
information is permanent and cannot be undone.

## Children's privacy

Parity requires all users to be at least 18 years old to create an
account, per our Terms of Service. Parity is not directed at, or intended
for, children, and we do not knowingly collect personal data from anyone
under 18. If you believe someone under 18 has created an account or
provided us with personal data, contact us and we will investigate and
delete it as appropriate.

## Where your data is stored

Your data is stored on Oracle Cloud Infrastructure, in the US East
(Ashburn) region.

## Changes to this policy

We may update this policy from time to time. When we do, we will change the
"Last updated" date at the top. Significant changes will be communicated
through the app or by other reasonable means. Continuing to use the service
after a change means you accept the updated policy.

## Contact

For any questions about this policy or your data, contact us at:

> richardtknepp@gmail.com

---

## Before this goes live

- [ ] Fill in the actual publish date at the top.
- [ ] Do not publish the "encrypted in transit" claim under Security until
      the Android client's cleartext-permitting network config and
      user-editable server URL are actually removed from the shipped app
      (Option A architecture change, not yet implemented as of this
      draft). Right now the claim would be aspirational, not true of the
      current build.
- [ ] Get the account deletion web page live and drop its URL into the
      "Account and data deletion" section above.
- [ ] Update the contact email here (and in the Terms of Service) once
      rich@rknepp.com or similar is live.
- [ ] Confirm the OCI US East (Ashburn) region has no data-residency
      implications you care about for any specific user base; not a GDPR
      problem in itself (adequacy/SCC mechanisms exist for EU-to-US
      transfer) but worth being aware you're transferring EU user data to
      the US if you ever get an EU signup.
