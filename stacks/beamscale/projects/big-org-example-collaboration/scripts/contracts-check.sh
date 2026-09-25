#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$PROJECT/../../../.." && pwd)"
TJSV="$ROOT/.local/bin/tjsv"
if [[ ! -x "$TJSV" ]]; then
  if command -v tjsv >/dev/null 2>&1; then TJSV="$(command -v tjsv)"
  elif [[ -x "$ROOT/node_modules/.bin/tjsv" ]]; then TJSV="$ROOT/node_modules/.bin/tjsv"
  else echo "missing tjsv" >&2; exit 2
  fi
fi
mkdir -p .artifacts/conformance
"$TJSV" check --typespec=contracts/typespec/main.tsp --schema=contracts/json-schema/domain.schema.json --instances=conformance/instances --report=.artifacts/conformance/typespec-json-schema.json --quiet
python3 "$ROOT/scripts/generate_contracts.py" "$PROJECT" --check
