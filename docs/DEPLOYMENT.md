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
