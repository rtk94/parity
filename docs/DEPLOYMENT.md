# Deployment

Parity runs as a **hosted service** on a single Oracle OCI compute
instance (Ubuntu 24.04). The backend is served **natively** — gunicorn
under systemd, fronted by nginx with certbot-managed TLS. There are two
independent environments on the box, **production** and **staging**.

> A containerized path (`backend/Dockerfile` + `compose.yaml`) also
> exists and is tested, kept ready for a future migration to Docker on a
> larger instance. The live deployment described here is the native one.

## Topology

```
                 nginx  (:80 / :443, TLS via certbot)
                   │
   api.parity.rknepp.com ─────────► gunicorn :5000   (production)
                   │                 /var/www/parity/backend
   staging-api.parity.rknepp.com ──► gunicorn :5001   (staging)
                                     /var/www/parity-staging/backend
```

Both `api.` and `staging-api.` (and `staging.`) resolve to the VPS.
nginx routes by `Host` header; no extra ports are exposed.

## Environments

|                | Production                         | Staging                                    |
| -------------- | ---------------------------------- | ------------------------------------------ |
| systemd unit   | `parity.service`                   | `parity-staging.service`                   |
| gunicorn       | `-w 2 -b 127.0.0.1:5000`           | `-w 1 -b 127.0.0.1:5001`                   |
| directory      | `/var/www/parity/backend`          | `/var/www/parity-staging/backend`          |
| virtualenv     | `.venv/`                           | `.venv/`                                   |
| database       | `instance/parity.db` (SQLite)      | `instance/parity.db` (separate SQLite)     |
| config         | `.env` (`FLASK_ENV=production`)    | `.env` (`FLASK_ENV=production`, own `SECRET_KEY`) |
| nginx vhost    | `/etc/nginx/sites-available/parity`| `/etc/nginx/sites-available/parity-staging`|
| domain         | `api.parity.rknepp.com`            | `staging-api.parity.rknepp.com`            |

Each environment is fully isolated: separate directory, virtualenv,
database, secret, systemd unit, and nginx vhost. Staging exists to bake
a release before it reaches production.

## Configuration & secrets

Each environment is configured entirely by its own `.env` in the service
working directory (`/var/www/parity/backend/.env`, and the
`parity-staging` equivalent), loaded by python-dotenv from
[`app/config.py`](../backend/app/config.py). `.env` is gitignored, so it
survives the `git reset --hard` on deploy. Keys:

- `SECRET_KEY` — **unique per environment**, secret. Generate with
  `python -c "import secrets; print(secrets.token_urlsafe(48))"`.
- `DATABASE_URL` — defaults to that environment's SQLite file.
- `FCM_CREDENTIALS_FILE` — path to the Firebase service-account JSON for
  push; unset ⇒ push disabled (see below).

> **Load order matters.** `Config` reads the environment when
> `app.config` is first imported, so `.env` must be loaded *before* that.
> `app/config.py` does this at module top. (A prior bug loaded `.env`
> too late in `app/__init__.py`, so every value silently fell back to its
> default — including `SECRET_KEY` running as the dev default in
> production. Fixed by loading `.env` in `app/config.py`; the systemd
> units need no `EnvironmentFile`.) After changing this, confirm a real
> secret is in use:
>
> ```bash
> cd /var/www/parity/backend
> .venv/bin/python -c "import app; print(app.create_app().config['SECRET_KEY'] != 'dev-only-not-secret')"  # expect True
> ```

## TLS

Certificates are issued and installed with `certbot --nginx` and renew
automatically via the system `certbot.timer`. To add a cert for a new
host:

```bash
sudo certbot --nginx -d <host> --non-interactive --agree-tos --redirect
```

## Deploying

### Staging

A helper script pulls the latest `main`, migrates, and restarts:

```bash
ssh ubuntu@<vps> /var/www/parity-staging/deploy-staging.sh
```

### Production

Production is updated manually, and only after the same commit has been
verified on staging. **Back up the database first** (see below).

```bash
ssh ubuntu@<vps>
cd /var/www/parity
git fetch origin main && git reset --hard origin/main
cd backend
.venv/bin/pip install -e .
.venv/bin/flask db upgrade
sudo systemctl restart parity.service
curl -s https://api.parity.rknepp.com/api/v1/health   # expect {"status":"ok",...}
```

`.env` and `instance/` are gitignored, so they survive the reset.

## Push notifications (FCM)

Push delivery (see [ADR-0001](adr/0001-push-notification-transport.md))
is **off until each environment is given a Firebase service-account
key**. With `FCM_CREDENTIALS_FILE` unset the backend uses a no-op sender,
so this can be rolled out staging-first with no risk to production.

Each environment points at its **own** Firebase project — production at
`parity-production`, staging at `parity-staging` — so do the following
once per environment (paths shown for production; use the
`parity-staging` equivalents for staging).

1. **Place the service-account key** outside the repo, owned by the
   service user and readable only by it:

   ```bash
   sudo install -o ubuntu -g ubuntu -m 600 \
     parity-production-firebase-adminsdk.json /etc/parity/fcm-production.json
   ```

   Generate the key in the Firebase console → Project settings → Service
   accounts → **Generate new private key**. It is a secret — never commit
   it, and keep it at `chmod 600`.

2. **Point the environment at it** by adding one line to that
   environment's `.env`:

   ```bash
   FCM_CREDENTIALS_FILE=/etc/parity/fcm-production.json
   ```

3. **Install the SDK.** `firebase-admin` is a base dependency, so the
   standard deploy step (`.venv/bin/pip install -e .`) pulls it in; run
   it now if you're enabling push outside a normal deploy.

4. **Restart** the service (`sudo systemctl restart parity.service`) and
   confirm boot is clean — a bad key logs a warning and falls back to the
   no-op sender rather than crashing:

   ```bash
   journalctl -u parity.service --since "1 min ago" | grep -i fcm   # expect nothing
   ```

The Android **release** build talks to the production Firebase project
and backend; **debug** builds talk to staging (the app pairs `BASE_URL`
and `google-services.json` per build type). To verify end to end: sign in
on a real device (it registers its push token), then have the
counterparty account create a pending expense — a notification should
arrive and deep-link to the relationship. This flow was verified on
staging against a physical device. Two gotchas seen during that test:
FCM **throttles** rapid repeated sends to one device (space out test
sends), and after enabling push confirm the app didn't fall back to the
no-op sender — with the credentials file set, `create_app().extensions["push_sender"]`
should be `FcmPushSender`, not `NullPushSender`.

**Rotating a key** (or after a leak): generate a new private key in the
same console screen, replace the file, and restart. The old key stops
working immediately.

## Outbound email (password reset)

Self-service password reset (see
[ADR-0002](adr/0002-password-reset-transport.md) and its amendment)
emails a single-use 8-digit code to the account's recovery address, and
registration sends a welcome message naming that address. Delivery is
**off until each environment is given SMTP settings** — with
`MAIL_SERVER` unset the backend uses a no-op sender, so
`POST /auth/password-reset/request` still returns `204` but delivers
nothing.

> This is not hypothetical: the first internal-testing release shipped
> with `MAIL_SERVER` unset, which made the whole reset flow dead on
> arrival while every endpoint reported success. **Configuring this is
> what makes password reset work at all** — treat it as part of the
> release, not an optional extra.

The transport is provider-agnostic SMTP. The hosted instance uses
**Resend**; any relay (SES, Postmark, a self-managed server) works by
changing these values alone.

### 1. Verify the sending domain

In the Resend dashboard, add `parity.rknepp.com` as a domain. It issues
DNS records to publish — typically an MX and a TXT (SPF) on a `send.`
subdomain, plus a `resend._domainkey` TXT for DKIM. Add them at the DNS
provider and wait for the dashboard to show the domain verified.

Publish a DMARC record too, starting permissively:

```
_dmarc.parity.rknepp.com  TXT  "v=DMARC1; p=none; rua=mailto:dmarc@rknepp.com"
```

Without SPF and DKIM aligned, reset codes land in spam — which fails
exactly as silently as having no relay at all.

### 2. Configure the environment

Mint an API key, then set in `.env` (see `backend/.env.example`):

```bash
MAIL_SERVER=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend                   # literal; Resend's SMTP username
MAIL_PASSWORD=re_xxxxxxxx              # the API key, not an account password
MAIL_USE_TLS=true
MAIL_FROM=no-reply@parity.rknepp.com   # must be on the verified domain
PASSWORD_RESET_LIFETIME_MINUTES=15
# PASSWORD_RESET_MAX_ATTEMPTS=5        # wrong guesses before a code burns
```

Check the current free-tier daily/monthly caps in the dashboard rather
than assuming them; exceeding a cap degrades to the same silent
non-delivery this section exists to prevent.

### 3. Verify

Restart the service and confirm the transport seam actually flipped:

```bash
cd /var/www/parity/backend
.venv/bin/python -c "import app; print(type(app.create_app().extensions['email_sender']).__name__)"
```

Expect `SmtpEmailSender`, not `NullEmailSender`. Then check end to end:
register a throwaway account with a real address (the welcome email
should arrive), request a reset, and confirm the code lands in the inbox
rather than spam.

Roll out **staging first** — it is the same one-line config change in
either environment, and a misconfigured relay is invisible from the API.

## Attachment storage

Expense attachments (see
[ADR-0003](adr/0003-attachment-storage.md)) store file bytes in
**S3-compatible object storage**, with only metadata in the database.
With `ATTACHMENT_S3_BUCKET` unset the backend falls back to the **local
filesystem** (`instance/attachments`), which is fine for dev but ties
files to one box in production — configure object storage for prod.

The client is provider-agnostic (boto3), so point it at OCI Object
Storage (the box is an OCI instance), Amazon S3, or Cloudflare R2. Set
in `.env` (see `backend/.env.example`):

```bash
ATTACHMENT_S3_BUCKET=parity-attachments
ATTACHMENT_S3_ENDPOINT_URL=https://<namespace>.compat.objectstorage.us-ashburn-1.oraclecloud.com
ATTACHMENT_S3_REGION=us-ashburn-1
ATTACHMENT_S3_ACCESS_KEY=<key>
ATTACHMENT_S3_SECRET_KEY=<secret>
# ATTACHMENT_MAX_BYTES=10485760   # 10 MB default
```

Create the bucket as **private** (attachments are financial records —
never world-readable); the app streams downloads through the
authenticated API rather than handing out public URLs. After
configuring, restart and verify an upload/download round-trips.

## Scheduled jobs

Two maintenance commands are meant to run on a timer. Both are Flask CLI
commands invoked from the backend virtualenv with the service
environment loaded (so `SECRET_KEY` and `DATABASE_URL` resolve the same
way the app does):

- **`flask run-recurring`** — materialises a pending expense for every
  recurring template that has come due. Run it **once a day**; it is
  idempotent within a day (a template fires at most once per
  invocation), so an occasional missed or doubled run is harmless.
- **`flask cleanup-tokens`** — purges expired and revoked `auth_token`
  rows. Run it periodically (e.g. weekly); it is purely housekeeping.

Example daily systemd timer (or cron `@daily`) for recurring generation:

```bash
cd /var/www/parity/backend && .venv/bin/flask run-recurring
```

## Backups

The production ledger is a single SQLite file
(`/var/www/parity/backend/instance/parity.db`), protected by two layers.

**On-box (automated).** `parity-backup.timer` runs
[`scripts/parity-backup.sh`](../scripts/parity-backup.sh) (installed at
`/usr/local/bin/parity-backup`) daily at 03:17 UTC. Each run takes an
online `sqlite3 .backup` snapshot (safe while gunicorn serves), verifies
it with `PRAGMA integrity_check`, gzips it into `/var/backups/parity/`
(root-only), and prunes local copies older than 14 days.

**Off-box (automated).** A pull host runs
[`scripts/parity-backup-pull.sh`](../scripts/parity-backup-pull.sh) on a
systemd **user** timer (`Persistent=true` + linger), which rsyncs
`/var/backups/parity/` off the server over SSH — elevating via the
server's passwordless sudo to read the root-only files — and keeps 90
days. This currently targets a desktop, so it is a *best-effort* copy;
see the follow-up below.

Take a snapshot or restore-check by hand:

```bash
sudo /usr/local/bin/parity-backup                        # snapshot now
latest=$(sudo ls -1t /var/backups/parity/*.db.gz | head -1)
sudo bash -c "gunzip -c '$latest' > /tmp/r.db && sqlite3 /tmp/r.db 'PRAGMA integrity_check;'; rm -f /tmp/r.db"
```

> **Follow-up:** the off-box copy depends on the desktop being on.
> Upgrade it to a durable off-site store — OCI Object Storage (the box
> is an OCI instance) with an instance principal, lifecycle retention,
> and a periodic automated restore test.

## Android release builds (Play Store)

The Play Store artifact is an **Android App Bundle** (`.aab`) built from
the `release` build type, which pins the production backend
(`https://api.parity.rknepp.com/`) and the `parity-production` Firebase
project — see the build-type comment in
[`android/app/build.gradle.kts`](../android/app/build.gradle.kts).

### Signing material

Release builds are signed with an **upload key** (RSA 4096, alias
`parity-upload`). Two files hold it, both gitignored:

| File | What it is |
| --- | --- |
| `android/upload-keystore.jks` | the PKCS12 keystore |
| `android/keystore.properties` | store/key passwords + alias |

> **Back both up offline** (password manager + an encrypted copy that is
> not this machine). They exist nowhere else. Losing them does not brick
> the app — Google Play App Signing holds the real app signing key, and
> an upload key can be reset through Play Console support — but the
> reset is manual and slow.

When `keystore.properties` is absent the release build still assembles,
just **unsigned**, so CI and fresh clones are unaffected. That mirrors
how the committed placeholder `google-services.json` keeps the Google
Services plugin working without the real per-environment configs.

### Cutting a build

Bump `versionCode` (and `versionName` if the release is user-visible) in
`android/app/build.gradle.kts` — Play rejects a re-used `versionCode` —
then:

```bash
cd android && ./gradlew clean bundleRelease
```

The bundle lands at `android/app/build/outputs/bundle/release/app-release.aab`.

Confirm it is signed before uploading — an unsigned bundle is rejected
at upload time:

```bash
unzip -l android/app/build/outputs/bundle/release/app-release.aab | grep -E 'META-INF/[^/]*\.(RSA|SF)$'
```

Two `META-INF/PARITY-U.*` entries means signed; no output means the
keystore was not picked up.

### Uploading

Play Console → **Testing → Internal testing → Create new release** →
upload the `.aab`. The first upload enrolls the app in Play App Signing;
accept it. Add testers by email list, then share the opt-in URL.

Listing copy (description, privacy policy, terms, data-safety answers)
lives in [`docs/play_store/`](play_store/).
