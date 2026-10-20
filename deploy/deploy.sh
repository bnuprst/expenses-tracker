#!/usr/bin/env bash
# Build and (re)start the production stack, then wait until the app reports healthy.
# Run from anywhere after `git pull`; safe to repeat.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE=(docker compose -f docker-compose.prod.yml)
# Behind the shared edge Caddy (bnuprst/vps-infra), this joins the app to the edge network.
# Absent elsewhere (e.g. a local trial run), where nothing proxies to the app.
[[ -f docker-compose.edge.yml ]] && COMPOSE+=(-f docker-compose.edge.yml)

[[ -f .env ]] || { echo "No .env - copy .env.example to .env and fill it in." >&2; exit 1; }
if grep -Eq '^EB_APP_ID=.+' .env && [[ ! -s .secrets/enablebanking.pem ]]; then
  echo "EB_APP_ID is set but .secrets/enablebanking.pem is missing or empty." >&2
  exit 1
fi
# Compose refuses to start without the secret file, even with bank sync off.
[[ -e .secrets/enablebanking.pem ]] || { install -d -m 700 .secrets; install -m 600 /dev/null .secrets/enablebanking.pem; }

echo "==> building"
"${COMPOSE[@]}" build --pull app

echo "==> starting"
"${COMPOSE[@]}" up -d

echo "==> waiting for app to report healthy"
for _ in $(seq 1 36); do
  if [[ "$("${COMPOSE[@]}" ps --format '{{.Health}}' app 2>/dev/null || true)" == "healthy" ]]; then
    echo "app is healthy."
    # Old build layers add up on a small disk.
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 5
done

echo "app did not become healthy in time. Last logs:" >&2
"${COMPOSE[@]}" logs --tail 100 app >&2
exit 1
