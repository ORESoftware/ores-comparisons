#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$PROJECT/../../../.." && pwd)"
TJSV="$ROOT/.local/bin/tjsv"
if [[ ! -x "$TJSV" ]]; then TJSV="$ROOT/node_modules/.bin/tjsv"; fi
test -x "$TJSV" || { echo "missing tjsv" >&2; exit 2; }
mkdir -p .artifacts/conformance
"$TJSV" check --typespec=contracts/typespec/main.tsp --schema=contracts/json-schema/domain.schema.json --instances=conformance/instances --report=.artifacts/conformance/typespec-json-schema.json --quiet
python3 "$ROOT/scripts/generate_contracts.py" "$PROJECT" --check
