#!/usr/bin/env bash
set -euo pipefail
workload_root="$(cd "${1:?workload root required}" && pwd)"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
validator_rev="$(jq -r '.typespec_json_schema_validator.rev' "$repo_root/toolchain.lock.json")"
validator_package="https://github.com/ORESoftware/typespec-json-schema-validator/archive/${validator_rev}.tar.gz"
evidence="$workload_root/generated/typespec-witness"
report="$workload_root/generated/tjsv-report.json"
mkdir -p "$evidence"
npx --yes --package "$validator_package" tjsv check \
  --typespec="$workload_root/contracts/main.tsp" \
  --schema="$workload_root/contracts/entities.schema.json" \
  --instances="$workload_root/conformance/instances" \
  --output-dir="$evidence" \
  --bundle-id=typespec.generated.schema.json \
  --contract-ir="$workload_root/generated/contract-ir.json" \
  --report="$report"
exec "$repo_root/scripts/generate-workload-contracts.mjs" "$workload_root" \
  --typespec-witness="$evidence/typespec.generated.schema.json"
