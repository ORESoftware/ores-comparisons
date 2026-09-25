#!/usr/bin/env bash
set -euo pipefail
SHARED="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$SHARED/../../../../../.." && pwd)"
cd "$SHARED"
source scripts/load-env.sh
export SCINTILLA_BASE_URL=http://127.0.0.1:8080
cd ../app
exec "$ROOT/.local/bin/scintilla" dev --project .
