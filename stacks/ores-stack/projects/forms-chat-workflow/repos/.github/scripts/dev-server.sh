#!/usr/bin/env bash
set -euo pipefail
SHARED="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$SHARED/../../../../../.." && pwd)"
cd "$SHARED"
source scripts/load-env.sh
cd ../app
exec "$ROOT/.local/bin/ores-stack" dev
