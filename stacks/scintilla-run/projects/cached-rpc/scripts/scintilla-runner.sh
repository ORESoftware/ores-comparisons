#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."; ROOT="$(cd ../../../.. && pwd)"; cd "$ROOT/.local/runtimes/scintilla-runner"
exec env HOST=127.0.0.1 PORT=8083 gleam run
