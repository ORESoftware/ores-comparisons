#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/load-env.sh
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f contracts/generated/sql/001_init.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f contracts/generated/sql/010_domain_constraints.sql
