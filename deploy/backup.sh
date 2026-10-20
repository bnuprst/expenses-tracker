#!/usr/bin/env bash
# Nightly logical backup of the database, kept on-box with a 14-day retention. Installed as a systemd
# timer (deploy/systemd/). pg_dump takes a consistent snapshot of a live database, so - unlike the
# SQLite-based apps on this box - nothing is stopped.
#
# The dumps are plaintext financial data: 0700 directory, 0600 files, never copied off-box casually.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

BACKUP_DIR=/var/backups/expenses-tracker
RETENTION_DAYS=14
COMPOSE=(docker compose -f docker-compose.prod.yml)
[[ -f docker-compose.edge.yml ]] && COMPOSE+=(-f docker-compose.edge.yml)

install -d -m 700 "${BACKUP_DIR}"
archive="${BACKUP_DIR}/expenses-$(date -u +%Y%m%dT%H%M%SZ).dump"

# Write to a temporary name first, so a failed dump never looks like a good one.
(umask 077 && "${COMPOSE[@]}" exec -T db pg_dump -U expenses -d expenses -Fc >"${archive}.partial")
mv "${archive}.partial" "${archive}"
echo "$(date -u --iso-8601=seconds) backed up to ${archive} ($(du -h "${archive}" | cut -f1))"

find "${BACKUP_DIR}" -name 'expenses-*.dump' -mtime "+${RETENTION_DAYS}" -delete
