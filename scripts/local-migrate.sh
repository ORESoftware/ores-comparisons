#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "${1:?project root required}" && pwd)"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
contract_set="$project_root/contracts/contract-set.json"
workload_rel="$(jq -r '.workload_root' "$contract_set")"
workload_root="$(cd "$project_root/$workload_rel" && pwd)"
"$repo_root/scripts/admit-and-generate.sh" "$workload_root"
port="${ORES_COMPARE_PG_PORT:-$(jq -r '.postgres.local_port' "$repo_root/toolchain.lock.json")}"
db="$(jq -r '.postgres.database' "$repo_root/toolchain.lock.json")"
user="$(jq -r '.postgres.user' "$repo_root/toolchain.lock.json")"
database_url="${DATABASE_URL:-postgresql://${user}:ores@127.0.0.1:${port}/${db}}"
psql "$database_url" -v ON_ERROR_STOP=1 -f "$workload_root/generated/sql/001_schema.sql"
psql "$database_url" -v ON_ERROR_STOP=1 -f "$workload_root/db/seeds/dev.sql"
