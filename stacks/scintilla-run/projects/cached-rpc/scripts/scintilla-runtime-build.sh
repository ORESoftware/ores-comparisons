#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."; ROOT="$(cd ../../../.. && pwd)"
case "${1:-}" in
 runner) (cd "$ROOT/.local/runtimes/scintilla-runner" && gleam build) ;;
 backend) (cd "$ROOT/.local/runtimes/scintilla-backend" && cargo build --locked) ;;
 *) exit 2 ;;
esac
