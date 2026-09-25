#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."; PORT="${PGPORT:-55438}"; DATA=".local/postgres/data"; mkdir -p "$(dirname "$DATA")"
if [[ ! -f "$DATA/PG_VERSION" ]]; then initdb -D "$DATA" -U postgres --auth-local=trust --auth-host=trust --encoding=UTF8 >/dev/null; fi
exec postgres -D "$DATA" -h 127.0.0.1 -p "$PORT"
