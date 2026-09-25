#!/usr/bin/env bash
set -euo pipefail
project_id="${1:?project id required}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
db="$(jq -r '.postgres.database' "$root/toolchain.lock.json")"
user="$(jq -r '.postgres.user' "$root/toolchain.lock.json")"
container="ores-comparisons-${project_id}-postgres"
exec docker exec "$container" pg_isready -U "$user" -d "$db"
