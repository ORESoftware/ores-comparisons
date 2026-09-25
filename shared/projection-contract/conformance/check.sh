#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
python3 "$ROOT/scripts/verify_projection_contract.py"
python3 "$ROOT/scripts/verify_toolchain_pins.py"
