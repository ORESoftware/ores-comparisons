#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$PROJECT/../../../.." && pwd)"
python3 "$ROOT/scripts/generate_contracts.py" "$PROJECT" --check
python3 "$ROOT/scripts/verify_toolchain_pins.py"
