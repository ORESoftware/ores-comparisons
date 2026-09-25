#!/usr/bin/env bash
set -euo pipefail
SHARED="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$SHARED/../../../../../.." && pwd)"
cd "$SHARED"
source scripts/load-env.sh
export BMSCL_SUPERVISOR_ROOT="$ROOT/.local/runtimes/bmscl-supervisor"
export BMSCL_COMPILER="$ROOT/.local/bin/bmscl-compiler"
test -x "$ROOT/.local/bin/bmscl" || { echo "run just tools-bootstrap first" >&2; exit 2; }
cd ../app
exec "$ROOT/.local/bin/bmscl" dev .
