#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/load-env.sh
ROOT="$(cd ../../../.. && pwd)"
export BMSCL_SUPERVISOR_ROOT="$ROOT/.local/runtimes/bmscl-supervisor"
export BMSCL_COMPILER="$ROOT/.local/bin/bmscl-compiler"
test -x "$ROOT/.local/bin/bmscl" || { echo "run just tools-bootstrap first" >&2; exit 2; }
exec "$ROOT/.local/bin/bmscl" dev .
