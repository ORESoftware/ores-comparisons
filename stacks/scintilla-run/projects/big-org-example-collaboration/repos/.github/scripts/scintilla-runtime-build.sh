#!/usr/bin/env bash
set -euo pipefail
SHARED="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$SHARED/../../../../../.." && pwd)"
case "${1:-}" in
  runner) (cd "$ROOT/.local/runtimes/scintilla-runner" && gleam build) ;;
  backend) (cd "$ROOT/.local/runtimes/scintilla-backend" && cargo build --locked) ;;
  *) echo "usage: $0 runner|backend" >&2; exit 2 ;;
esac
