#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/load-env.sh
ROOT="$(cd ../../../.. && pwd)"
export SCINTILLA_BASE_URL=http://127.0.0.1:8080
exec "$ROOT/.local/bin/scintilla" dev --project .
