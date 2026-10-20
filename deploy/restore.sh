#!/usr/bin/env bash
# Restore a dump produced by backup.sh, replacing the database's current contents.
#
# Usage: deploy/restore.sh /var/backups/expenses-tracker/expenses-<timestamp>.dump
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ARCHIVE="$(realpath "${1:?Usage: $0 <path-to-dump>}")"
[[ -f "${ARCHIVE}" ]] || { echo "No such file: ${ARCHIVE}" >&2; exit 1; }

COMPOSE=(docker compose -f docker-compose.prod.yml)
[[ -f docker-compose.edge.yml ]] && COMPOSE+=(-f docker-compose.edge.yml)

echo "This restores '${ARCHIVE}', REPLACING the current database."
read -r -p "Type 'restore' to continue: " confirm
[[ "${confirm}" == "restore" ]] || { echo "Aborted."; exit 1; }

echo "==> stopping app"
"${COMPOSE[@]}" stop app

echo "==> restoring"
"${COMPOSE[@]}" up -d db
"${COMPOSE[@]}" exec -T db pg_restore -U expenses -d expenses --clean --if-exists --no-owner <"${ARCHIVE}"

echo "==> starting app"
"${COMPOSE[@]}" up -d app

echo "Restore complete. Log in and check it before trusting it."
