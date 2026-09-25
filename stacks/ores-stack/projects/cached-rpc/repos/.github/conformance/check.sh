#!/usr/bin/env bash
set -euo pipefail
SHARED="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$SHARED/../../../../../.." && pwd)"
python3 "$ROOT/scripts/generate_contracts.py" "$SHARED" --check
python3 "$ROOT/scripts/verify_toolchain_pins.py"
