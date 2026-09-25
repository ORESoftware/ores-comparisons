#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/load-env.sh
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f contracts/generated/sql/002_seed.sql
