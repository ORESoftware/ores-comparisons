#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$PROJECT/../../../.." && pwd)"
cd "$PROJECT"
source scripts/load-env.sh
export SCINTILLA_BASE_URL=http://127.0.0.1:8080
cd repos/app
exec "$ROOT/.local/bin/scintilla" dev --project .
