#!/usr/bin/env bash
set -euo pipefail
stack="${1:?stack required}"
project_root="$(cd "${2:-.}" && pwd)"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$repo_root/scripts/local-migrate.sh" "$project_root"
cd "$project_root"
case "$stack" in
  beamscale)
    exec bmscl dev . --poll-ms 250
    ;;
  scintilla-run)
    host="$(jq -r '.scintilla_backend.host' "$repo_root/toolchain.lock.json")"
    port="$(jq -r '.scintilla_backend.port' "$repo_root/toolchain.lock.json")"
    export SCINTILLA_BASE_URL="http://${host}:${port}"
    exec scintilla dev --project .
    ;;
  ores-stack)
    pg_port="${ORES_COMPARE_PG_PORT:-$(jq -r '.postgres.local_port' "$repo_root/toolchain.lock.json")}"
    export DATABASE_URL="postgresql://ores:ores@127.0.0.1:${pg_port}/ores_compare"
    exec ores-stack dev
    ;;
  *)
    echo "unsupported stack: $stack" >&2
    exit 2
    ;;
esac
