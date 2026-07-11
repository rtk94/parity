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
arrive and deep-link to the relationship.

**Rotating a key** (or after a leak): generate a new private key in the
same console screen, replace the file, and restart. The old key stops
working immediately.

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
