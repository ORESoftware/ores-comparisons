#!/usr/bin/env bash
set -euo pipefail
if [[ -f .env ]]; then set -a; source ./.env; set +a; fi
export PGPORT="${PGPORT:-55438}"
export DATABASE_URL="${DATABASE_URL:-postgresql://postgres@127.0.0.1:${PGPORT}/postgres}"
export BIND_ADDR="${BIND_ADDR:-127.0.0.1:3110}"
