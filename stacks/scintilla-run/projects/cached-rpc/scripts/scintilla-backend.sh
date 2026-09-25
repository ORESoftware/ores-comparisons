#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."; ROOT="$(cd ../../../.. && pwd)"; cd "$ROOT/.local/runtimes/scintilla-backend"
exec env HOST=127.0.0.1 PORT=8080 SCINTILLA_RUNNER_URL=http://127.0.0.1:8083 cargo run --locked
