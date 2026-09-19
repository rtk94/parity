# Parity: Google Play Data Safety Form Mapping

## Sources

All category names, data type definitions, and purpose definitions below are
taken verbatim from:

- **Primary taxonomy:** "Provide information for Google Play's Data safety
  section", Play Console Help, article 10787469.
  https://support.google.com/googleplay/android-developer/answer/10787469
  (Data types table and Purposes table, under "Data types and purposes".)
- **Account deletion:** "Understanding Google Play's app account deletion
  requirements", Play Console Help, article 13327111.
  https://support.google.com/googleplay/android-developer/answer/13327111
- **User Data policy** (referenced by both of the above), article 10144311.
  https://support.google.com/googleplay/android-developer/answer/10144311

Form location: Play Console > **App content** > **Data safety**.

## Two corrections to the premise before the mapping

**1. The form has no per-category free-text explanation fields.**

You asked for "the free-text explanation fields Google requires for financial
and account data." Those do not exist. The Data safety form is entirely
structured: multiple-choice and single-choice questions answered TRUE/FALSE
per data type. This is visible in Google's own sample CSV export, where every
row is a `Question ID` / `Response` / `Response value` triple with answer
requirements of `REQUIRED`, `MULTIPLE_CHOICE`, or `SINGLE_CHOICE`. There is no
prose box for "explain your financial data collection."

The only free-text input in the flow is the **account deletion web URL** (see
Section 4).

Explanatory prose is still needed, just not in the form. It belongs in your
privacy policy (which Google cross-checks against the form), your account
deletion web page, and a held-in-reserve explanation if a reviewer queries the
Financial info declaration. Drafts for all three are in Section 5.

**2. The Data safety form is required for your closed test.**

Article 10787469: all developers with an app published on Google Play must
complete the form, "including apps on closed, open, or production testing
tracks." Only **internal** testing tracks are exempt. Your closed test needs
this form completed and a privacy policy URL live.

---

## Section 1: Data collection and security

Three gate questions.

### 1.1 "Does your app collect or share any of the required user data types?"

**Answer: Yes.**

Google defines "collect" as transmitting data off the device. Your Android
client posts credentials and ledger entries to your OCI backend, so this is
collection regardless of the fact that you are the only recipient.

### 1.2 "Is all of the user data collected by your app encrypted in transit?"

**Answer: Yes, but do not check this until you fix the client.**

This is the highest-risk answer on the form for you, and it is a code problem,
not a paperwork problem.

Phase 6 ships `network_security_config.xml` with **cleartext permitted for all
domains**, and the ConnectToServerScreen explicitly accepts any URL with an
`http` or `https` scheme (the spec's validation step 2 allows both). That
design was correct for the self-hosted model it was written for. Under the
hosted SaaS model it means a shipped build can transmit usernames, passwords,
and ledger data over plaintext HTTP if a URL with that scheme is entered.

Declaring "all user data is encrypted in transit" while shipping that config
is a declaration that does not match app behavior, which article 10787469
describes as grounds for enforcement action.

Fix before submitting. Options, in order of preference:

1. Drop the server-URL screen entirely and hard-code the OCI base URL over
   HTTPS. This matches the SaaS pivot and removes the problem at the root.
   It also removes the closed-testing onboarding stall I flagged earlier.
2. Keep the screen but restrict the scheme to `https` only and remove the
   blanket `cleartextTrafficPermitted` from the network security config.

Once either is done, "Yes" is accurate and you should check it.

### 1.3 "Do you provide a way for users to request that their data is deleted?"

**Answer: depends on your privacy policy placeholder, but the bar is low.**

Article 10787469 FAQ ("Is there a specific type of mechanism that I must
provide...") states there is no prescribed mechanism, and lists acceptable
examples including in-app features, contact forms, or **a dedicated email
alias**. So the manual-by-email option you were considering in the privacy
policy does qualify for this particular question, and you can answer Yes.

Do not confuse this with the separate Account Deletion Requirement, which is
stricter and which you currently do not meet. See Section 4.

---

## Section 2: Data types

Select these and only these. Everything you listed maps to four types across
three categories, with two items that have no home in the taxonomy at all.

### Recommended selections

| Your data | Category | Data type | Google's definition (verbatim) |
|---|---|---|---|
| Username | Personal info | **User IDs** | "Identifiers that relate to an identifiable person. For example, an account ID, account number, or account name." |
| Display name | Personal info | **Name** | "How a user refers to themselves, such as their first or last name, or nickname." |
| Expense/payment amounts, timestamps | Financial info | **Purchase history** | "Information about purchases or transactions a user has made." |
| Balances, who-owes-whom | Financial info | **Other financial info** | "Any other financial information such as user salary or **debts**." |
| Expense/payment descriptions | App activity | **Other user-generated content** | "Any other user-generated content not listed here, or in any other section. For example, user bios, notes, or open-ended responses." |

Notes on the two Financial info selections:

- **Purchase history** covers the individual ledger entries. Each entry is a
  record of a transaction the user made. The fact that you did not process the
  transaction is irrelevant to the definition, which is about the information,
  not the mechanism.
- **Other financial info** is the stronger and less obvious one. Google's
  definition names **debts** explicitly, and a who-owes-whom balance is exactly
  that. Do not skip it on the theory that Purchase history already covers the
  ledger.
- **User payment info** ("credit card number") and **Credit score**:
  correctly excluded. You hold no payment instruments. Article 10787469's FAQ
  on external payment services is not even reachable for you, since you have no
  payment integration at all.

Note on **Other user-generated content**: descriptions are free text and can
contain anything the user types. Article 10787469's FAQ states that if you
collect one data type during the collection of another, you should disclose
both. The description field rides along with the financial record but is
independently free-form, so declare it.

### Explicitly NOT selected

| Category / type | Why not |
|---|---|
| Location (Approximate, Precise) | Not collected. No location permissions, no IP-to-location inference. |
| Personal info > Email address | You do not collect one. Worth noting: this is why manual deletion-by-email is awkward, since you have no verified contact channel for any account. |
| Personal info > Address, Phone number, Race and ethnicity, Political or religious beliefs, Sexual orientation, Other info | Not collected. |
| Health and fitness | Not collected. |
| Messages (Emails, SMS or MMS, Other in-app messages) | Not collected. Expense descriptions are not messages between users; they are annotations on a record. Declaring "Other in-app messages" would overstate. |
| Photos and videos, Audio files, Files and docs, Calendar | Not collected. No attachments feature (deferred to roadmap). |
| App activity > App interactions, In-app search history, Installed apps, Other actions | Not collected. No analytics SDK, no interaction telemetry. |
| Web browsing | Not collected. |
| App info and performance > Crash logs, Diagnostics, Other app performance data | Not collected. No crash reporting SDK. Phase 6 deliberately excluded analytics and crash reporting. If you ever add Firebase Crashlytics, this changes and the form must be updated. |
| Device or other IDs | Not collected. See the auth token discussion in Section 6. |

---

## Section 3: Data usage and handling

Answer these per data type. Your answers are near-identical across all five.

### Universal answers (apply to every selected type)

- **Is this data collected, shared, or both?** → **Collected only.**

  Nothing is Shared. Article 10787469 excludes transfers to a "service
  provider," defined as "an entity that processes user data on behalf of the
  developer and based on the developer's instructions," and its FAQ names "a
  cloud provider hosting user data from your app for your use" as a typical
  service provider. **Oracle Cloud Infrastructure is a service provider, not a
  third party.** Hosting on OCI does not make this "Shared." Answer Collected
  only, for every type.

- **Is this data processed ephemerally?** → **No**, for all five. It is all
  persisted in your SQLite database indefinitely.

- **Is this data required, or can users choose whether it's collected?** →
  **Data collection is required** for all five. There is no opt-out toggle
  anywhere in the app, and article 10787469 says to declare data as required
  where the app's primary functionality requires it. See Section 6 for a note
  on the arguable case for the ledger types.

### Purposes, per type

Drawn from the Purposes table in article 10787469.

| Data type | Purposes to select | Reasoning |
|---|---|---|
| **User IDs** (username) | App functionality; Account management; *(consider)* Fraud prevention, security, and compliance | App functionality = "authenticate users". Account management = "enable users to create accounts... log in to your app, or verify their credentials". See Section 6 on the third one. |
| **Name** (display name) | App functionality; Account management | Shown to the counterparty in-app (app functionality) and is an account attribute (account management). |
| **Purchase history** (ledger entries) | App functionality | The entries are the product. No other purpose applies. |
| **Other financial info** (balances) | App functionality | Same. |
| **Other user-generated content** (descriptions) | App functionality | Same. |

**Do not select** Analytics, Developer communications, Advertising or
marketing, or Personalization for anything. None apply, and each one that
appears in your listing is a claim you would have to defend.

---

## Section 4: Data deletion

This is the section that will block you, and it is not really a Data safety
question. Flagging it prominently because it was not in your prompt and it has
a code dependency with a multi-day lead time.

### The requirement

Per article 13327111, Google's User Data policy Account Deletion Requirement:
if your app **allows users to create an account from within the app**, you must
provide **both**:

1. **An in-app path** for users to delete their account and associated data.
2. **A web link resource** where users can request account and data deletion
   without reinstalling the app. This URL is entered in a field in the Data
   safety form.

Parity's RegisterScreen creates accounts in-app via `POST /api/v1/auth/register`.
The requirement applies. You currently have neither piece.

Also note from article 13327111: temporary deactivation, disabling, or
"freezing" an account does **not** qualify. Actual data deletion is required.

### Why this is not a quick fix for Parity specifically

Three problems, in increasing order of difficulty:

1. **No deletion endpoint exists.** Nothing in Phases 1 through 5 deletes a
   user or their data.

2. **The Phase 3 triggers actively prevent it.** `trg_expense_no_delete`,
   `trg_expense_share_no_delete`, `trg_payment_no_delete`, and
   `trg_relationship_no_delete` all RAISE unconditionally on DELETE. Any
   deletion implementation has to either drop the triggers inside a maintenance
   transaction and recreate them, or take an anonymization approach that
   overwrites identifying fields instead of deleting rows. Anonymization runs
   into the immutable-columns triggers too, since `created_by_user_id` and the
   description column are both protected.

3. **The two-party problem, which is a genuine design question.** If user A
   deletes their account, what happens to user B's ledger? B's record of a real
   shared financial history disappears or becomes half-anonymous. Google
   requires deletion of "the user data associated with that app account," but
   the ledger data is jointly generated and B has a legitimate interest in it.
   You need a defensible position here before you write code. The usual
   resolution is to anonymize the departing user's identity (username, display
   name) while preserving the ledger rows for the counterparty, and to document
   that clearly in the privacy policy. That is defensible under the policy's
   allowance for retaining data with clear user disclosure, but it is a decision
   you should make deliberately rather than discover mid-implementation.

### What to do

For the closed test, the pragmatic path is a hosted web deletion page (a static
page with a form or a clearly stated email address and process) plus a manual
deletion procedure you actually perform. That satisfies the web-link half and,
combined with Section 1.3, lets you answer the Data safety deletion question
honestly.

The in-app path is required for production. Phase 9 (polish and settings) is
the natural home for a "Delete account" item in settings, which means the
backend deletion or anonymization endpoint needs to exist by then. Worth adding
to ROADMAP.md and as a GitHub issue now, since it is a compliance dependency
rather than a nice-to-have.

Whatever you decide, the deletion approach must read identically in three
places: this form, the privacy policy, and the deletion web page. Article
10787469 states Google reviews for discrepancies between declarations and
behavior.

---

## Section 5: Supporting prose you actually need

Not form fields (see the premise correction at the top), but required for
consistency and useful to have drafted.

### 5.1 Privacy policy alignment text

Your privacy policy must name the same data categories the form declares. The
current draft covers account information, ledger data, connection data,
authentication data, and technical data. Mapping check:

| Form declaration | Covered in privacy policy draft? |
|---|---|
| User IDs (username) | Yes, "Account information" |
| Name (display name) | Yes, "Account information" |
| Purchase history (ledger entries) | Yes, "Ledger data" |
| Other financial info (balances) | Yes, "Ledger data", covers who-owes-whom |
| Other user-generated content (descriptions) | Yes, "Ledger data", covers descriptions |

No gaps. The policy also discloses IP address processing, which the form does
not declare; that direction (policy discloses more than form) is safe. The
dangerous direction is the reverse.

### 5.2 Account deletion page copy

For the web resource required by article 13327111. Fill placeholders.

> **Delete your Parity account**
>
> You can ask us to delete your Parity account and the personal data
> associated with it at any time.
>
> **How to request deletion**
>
> Send an email to [PLACEHOLDER: deletion request email] from an address you
> can be contacted at, including the username of the account you want deleted.
> We will confirm the request before acting on it, so that no one else can
> delete your account.
>
> **What gets deleted**
>
> [PLACEHOLDER: state exactly what is deleted and what, if anything, is
> retained. If you adopt the anonymization approach, say so plainly here, for
> example: "Your username and display name are permanently removed. Expense and
> payment records you shared with another person are kept in that person's
> ledger, with your identity removed, because those records are also their
> financial history."]
>
> **How long it takes**
>
> [PLACEHOLDER: state a timeframe, for example "within 30 days of confirming
> your request".]
>
> Deletion is permanent and cannot be undone.
>
> For more on how we handle your data, see our Privacy Policy at
> [PLACEHOLDER: privacy policy URL].

### 5.3 Held-in-reserve explanation for the Financial info declaration

Not submitted anywhere, but have it ready if a reviewer questions why an app
with no payment integration declares Financial info. Keeping this consistent
with your other artifacts is the point.

> Parity is a record-keeping ledger for shared expenses between two users. It
> does not process payments, hold funds, or connect to any financial
> institution, and it collects no payment instruments. It declares Purchase
> history because users record transactions they have made, and Other financial
> info because the app computes and stores a balance representing a debt between
> two users. Both are user-entered records rather than data obtained from a
> payment processor.

---

## Section 6: Flags, ambiguities, and judgment calls

Ordered by how much they matter.

### 6.1 Blocking: no account deletion mechanism

Covered in Section 4. This is the item most likely to cost you a rejection or a
post-publication enforcement notice. It has a backend code dependency that
interacts with your immutability triggers. Start it now, not after the closed
test.

### 6.2 Blocking: cleartext traffic vs "encrypted in transit"

Covered in Section 1.2. Fix the network security config and the URL scheme
validation before you check that box.

### 6.3 Ambiguous: relationship / pairing metadata

**Your prompt listed this as collected data. I did not map it to a data type,
and this is the call I am least confident about.**

The candidates:

- **Contacts.** Google's definition: "Information about the user's contacts
  such as contact names, message history, and **social graph information like
  usernames**, contact recency, contact frequency, interaction duration and call
  history." The phrase "social graph information like usernames" is a
  startlingly close fit for a `relationship` row, which is exactly a pair of
  usernames plus status.
- **Nothing.** The relationship row is composed entirely of data already
  declared (two user IDs) plus app-internal state (status, currency). You are
  not reading the device address book, you hold no `READ_CONTACTS` permission,
  and declaring Contacts would show "Contacts" in your store listing, which most
  users will read as "this app reads my phone contacts." That is arguably a
  misleading over-disclosure.

I lean toward **not declaring Contacts**, on the reasoning that the category is
oriented toward device contact data and that the underlying identifiers are
already declared under User IDs. But the definition's "social graph" language
genuinely cuts the other way, and under-disclosure carries more enforcement
risk than over-disclosure.

If you want to be conservative, declare Contacts, purpose App functionality
only. If you want the store listing to be non-misleading, leave it off and rely
on User IDs. Your call, and worth a second opinion. This is the one item here I
would not treat as settled.

### 6.4 No taxonomy home: passwords

**There is no password or credentials data type in Google's taxonomy.** Check
the Data types table in article 10787469: Personal info covers Name, Email
address, User IDs, Address, Phone number, Race and ethnicity, Political or
religious beliefs, Sexual orientation, and Other info. None of these is a
password, and "Other info" is defined by examples of personal attributes ("date
of birth, gender identity, veteran status"), not credentials.

So: **do not declare the password as a data type.** There is nowhere to put it.
This is not an oversight on your part; the form simply does not model
credentials. It is covered implicitly by the account management purpose on User
IDs, and by the encryption-in-transit declaration.

The argon2 hashing is a good practice, but note the form has no field to
disclose at-rest hashing either. It belongs in your privacy policy, where you
already have it.

### 6.5 No taxonomy home: auth tokens

Similar situation, with a wrinkle. The candidate is **Device or other IDs**:
"Identifiers that relate to an individual device, browser or app. For example,
an IMEI number, MAC address, Widevine Device ID, Firebase installation ID, or
advertising identifier."

An auth token does not relate to a device, browser, or app. It relates to a
session for an account, it is generated server-side rather than read from the
device, and it is not usable for cross-app or cross-session tracking. Article
10787469's FAQ on identifiers reinforces the distinction: "an identifier related
to a specific in-app event, but that does not reasonably relate to an individual
device, browser or app, would not need to be disclosed as 'Device or other
IDs'."

**Recommendation: do not declare.** Declaring Device or other IDs would put a
tracking-flavored badge on your listing that misrepresents what you do.

### 6.6 Unresolved: at-rest encryption on OCI

Your placeholder. It does not affect any Data safety form answer, because the
form only asks about encryption **in transit**. There is no at-rest question.

It still matters for your privacy policy, where I already declined to claim it.
Two things to check on the OCI side: whether the block volume holding
`parity.db` is encrypted (OCI block volumes are encrypted by default, but
confirm for your specific instance rather than assuming), and whether you are
running on a Compute instance with an attached volume versus any managed
database service, since the answer differs. Confirm before adding any at-rest
claim anywhere.

### 6.7 Unresolved: IP addresses and server access logs

Not declarable here, but worth closing out. Article 10787469's FAQ says to
disclose IP address collection "based on their particular usage and practices,"
and gives location inference as the triggering example. You use IPs for rate
limiting (Phase 3, `get_remote_address`), not location, and there is no data
type in the taxonomy that fits rate-limit IP usage. So: nothing to declare on
the form.

The open question is your **server access logs**. If nginx, Caddy, or whatever
terminates TLS on the OCI host persists IPs to disk, that is retained personal
data. It still does not map to a Data safety data type, but it does belong in
the privacy policy retention section, which I already flagged there. Check your
logging config once and settle it for both documents.

### 6.8 Minor: "required" vs "optional" for ledger data

I recommended Required for all five types. The arguable case: a user could
register and never enter an expense, so ledger data collection is in some sense
elective.

I do not think that flies. Google's test is whether the app's primary
functionality requires the data type, and "optional" is defined around opt-in
and opt-out controls, which you do not have. Recording expenses is the primary
functionality. Required is correct. Noting it only so you are not surprised if
someone raises it.

### 6.9 Housekeeping: the form is global and must stay current

Article 10787469: one form per package name, covering the sum of data practices
across all versions currently distributed, agnostic to track, version, and
region. Practical consequences for you:

- The form you submit for closed testing is the same form that governs
  production. There is no separate test-track declaration.
- Phases 7 and 8 add no new data types on this mapping, since the ledger types
  are already declared. But if you later add attachments (roadmap item), that
  is Photos and videos plus Files and docs. Recurring expenses, categories, and
  tags do not add types. Push notifications would add Developer communications
  as a purpose and probably Device or other IDs for the FCM token.
- Any crash reporting or analytics SDK you add later changes this form
  materially. Update it in the same release, not after.

---

## Pre-submission checklist

- [ ] Fix network security config and URL scheme validation, or hard-code the
      HTTPS OCI base URL (Section 1.2)
- [ ] Decide the deletion approach, including the two-party question (Section 4)
- [ ] Stand up the account deletion web page and get its URL (Section 4, 5.2)
- [ ] Publish the privacy policy at a live URL and fill its placeholders
- [ ] Confirm OCI at-rest encryption posture and server access log retention
      (Sections 6.6, 6.7)
- [ ] Make the Contacts call (Section 6.3)
- [ ] Verify the privacy policy names every data category the form declares
      (Section 5.1)
- [ ] Add in-app account deletion to the Phase 9 scope and to ROADMAP.md
- [ ] Complete the form, review the store listing preview, submit

## Standing caveat

This is a mapping of your described data handling onto Google's published
taxonomy, not legal advice. Article 10787469 is explicit that "only you possess
all the information required to complete the Data safety form" and that you
alone are responsible for the accuracy of the declarations. Verify each answer
against the app you actually ship.
