#!/usr/bin/env sh
set -eu

run_suites() {
  bin="$1"
  "$bin" tests/main.ores
  "$bin" tests/embeddings.ores
}

sh ./scripts/verify-source-invariants.sh

if [ -n "${ORESLANG_BIN:-}" ]; then
  run_suites "$ORESLANG_BIN"
  exit 0
fi

if command -v oreslang >/dev/null 2>&1; then
  run_suites oreslang
  exit 0
fi

echo "oreslang compiler/runtime not found; set ORESLANG_BIN or install the oreslang CLI" >&2
exit 2
