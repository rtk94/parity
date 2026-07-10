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

The production ledger lives in a single SQLite file
(`/var/www/parity/backend/instance/parity.db`). Take a consistent
snapshot with the SQLite backup API before any migration or deploy:

```bash
sqlite3 /var/www/parity/backend/instance/parity.db \
  ".backup '/var/backups/parity-$(date +%F-%H%M).db'"
```

> **TODO:** automate this on a schedule (cron/systemd timer) with
> off-box retention. Regular, tested, off-host backups are a
> prerequisite for real user data — treat this as a launch blocker.
