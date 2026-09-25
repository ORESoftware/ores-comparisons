#!/usr/bin/env bash
set -euo pipefail
SHARED="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$SHARED/../../../../../.." && pwd)"
cd "$ROOT/.local/runtimes/scintilla-runner"
exec env HOST=127.0.0.1 PORT=8083 gleam run
