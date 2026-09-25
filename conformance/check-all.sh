#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 scripts/generate_all_contracts.py --check

if [[ -x .local/bin/tjsv ]]; then
  TJSV="$ROOT/.local/bin/tjsv"
elif command -v tjsv >/dev/null 2>&1; then
  TJSV="$(command -v tjsv)"
elif [[ -x node_modules/.bin/tjsv ]]; then
  TJSV="$ROOT/node_modules/.bin/tjsv"
else
  echo "missing tjsv; run npm install or just tools-bootstrap" >&2
  exit 2
fi

for project in stacks/*/projects/*/ benchmarks/; do
  [[ -f "$project/contracts/typespec/main.tsp" ]] || continue
  echo "==> $project"
  mkdir -p "$project/.artifacts/conformance"
  "$TJSV" check     --typespec="$project/contracts/typespec/main.tsp"     --schema="$project/contracts/json-schema/domain.schema.json"     --instances="$project/conformance/instances"     --report="$project/.artifacts/conformance/typespec-json-schema.json"     --quiet
  bash "$project/conformance/check.sh"
done
