#!/usr/bin/env bash
set -euo pipefail
project_id="${1:?project id required}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="$(jq -r '.postgres.image' "$root/toolchain.lock.json")"
port="${ORES_COMPARE_PG_PORT:-$(jq -r '.postgres.local_port' "$root/toolchain.lock.json")}"
db="$(jq -r '.postgres.database' "$root/toolchain.lock.json")"
user="$(jq -r '.postgres.user' "$root/toolchain.lock.json")"
container="ores-comparisons-${project_id}-postgres"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker rm -f "$container" >/dev/null 2>&1 || true
exec docker run --rm --name "$container" \
  -e "POSTGRES_DB=$db" \
  -e "POSTGRES_USER=$user" \
  -e "POSTGRES_PASSWORD=ores" \
  -p "127.0.0.1:${port}:5432" \
  "$image"
