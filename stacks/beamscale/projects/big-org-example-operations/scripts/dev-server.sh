#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$PROJECT/../../../.." && pwd)"
cd "$PROJECT"
source scripts/load-env.sh
export BMSCL_SUPERVISOR_ROOT="$ROOT/.local/runtimes/bmscl-supervisor"
export BMSCL_COMPILER="$ROOT/.local/bin/bmscl-compiler"
test -x "$ROOT/.local/bin/bmscl" || { echo "run just tools-bootstrap first" >&2; exit 2; }
cd repos/app
exec "$ROOT/.local/bin/bmscl" dev .
