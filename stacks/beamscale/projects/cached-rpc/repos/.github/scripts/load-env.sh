#!/usr/bin/env bash
set -euo pipefail
if [[ -f .env ]]; then
  set -a
  source ./.env
  set +a
fi
export PGPORT="${PGPORT:-55434}"
export DATABASE_URL="${DATABASE_URL:-postgresql://postgres@127.0.0.1:${PGPORT}/postgres}"
