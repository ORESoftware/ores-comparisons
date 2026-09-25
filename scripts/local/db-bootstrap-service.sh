#!/usr/bin/env bash
set -euo pipefail
project="$(basename "$PWD")"
stack="$(basename "$(dirname "$(dirname "$PWD")")")"
container="ores-cmp-${stack}-${project}-pg"
repo_root="$(cd ../../../.. && pwd)"
mkdir -p .runtime
rm -f .runtime/db-ready
for _ in $(seq 1 30); do
  if docker exec "$container" pg_isready -U postgres -d ores_comparisons >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d ores_comparisons < "$repo_root/generated/sql/0001_init.sql"
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d ores_comparisons < "$repo_root/generated/sql/0002_seed.sql"
touch .runtime/db-ready
trap 'rm -f .runtime/db-ready' EXIT
while :; do sleep 3600; done
