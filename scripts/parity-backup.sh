#!/usr/bin/env bash
# Consistent, verified snapshot of the Parity production database.
# Deployed to the server as /usr/local/bin/parity-backup and run by
# parity-backup.timer (daily). See docs/DEPLOYMENT.md.
set -euo pipefail

DB="/var/www/parity/backend/instance/parity.db"
DEST="/var/backups/parity"
RETAIN_DAYS=14
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="${DEST}/parity-${stamp}.db"

install -d -m 700 "$DEST"

# Online backup — safe while gunicorn is serving (SQLite handles locking).
sqlite3 "$DB" ".backup '${out}'"

# Verify the snapshot before trusting it.
check="$(sqlite3 "$out" 'PRAGMA integrity_check;')"
if [ "$check" != "ok" ]; then
  echo "parity-backup: integrity check FAILED for ${out}: ${check}" >&2
  rm -f "$out"
  exit 1
fi

gzip -f "$out"
chmod 600 "${out}.gz"

# Prune old local backups.
find "$DEST" -maxdepth 1 -name 'parity-*.db.gz' -mtime "+${RETAIN_DAYS}" -delete

echo "parity-backup: wrote ${out}.gz ($(du -h "${out}.gz" | cut -f1)); $(ls -1 "$DEST"/parity-*.db.gz 2>/dev/null | wc -l) kept"
