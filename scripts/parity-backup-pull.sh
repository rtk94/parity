#!/usr/bin/env bash
# Pull Parity DB backups off the server to this (pull) host — an off-box
# copy. Runs headless on a systemd user timer; see docs/DEPLOYMENT.md.
#
# The server's backups are root-only, so rsync elevates on the remote via
# the login user's passwordless sudo. Override via env:
#   PARITY_SSH_TARGET  ssh target        (default: ubuntu@YOUR_VPS)
#   PARITY_SSH_KEY     identity file      (default: ~/.ssh/id_ed25519)
#   PARITY_BACKUP_DIR  local destination  (default: ~/backups/parity)
#   PARITY_RETAIN_DAYS local retention    (default: 90)
set -euo pipefail

TARGET="${PARITY_SSH_TARGET:-ubuntu@YOUR_VPS}"
KEY="${PARITY_SSH_KEY:-$HOME/.ssh/id_ed25519}"
DEST="${PARITY_BACKUP_DIR:-$HOME/backups/parity}"
RETAIN_DAYS="${PARITY_RETAIN_DAYS:-90}"

mkdir -p "$DEST"
rsync -az --rsync-path="sudo rsync" \
  -e "ssh -o BatchMode=yes -o IdentitiesOnly=yes -i ${KEY} -o ConnectTimeout=15" \
  "${TARGET}:/var/backups/parity/" "${DEST}/"

# Keep a longer local history than the server (which retains 14 days).
find "$DEST" -maxdepth 1 -name 'parity-*.db.gz' -mtime "+${RETAIN_DAYS}" -delete
