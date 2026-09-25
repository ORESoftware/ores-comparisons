#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(jq -r '.typespec_json_schema_validator.version' "$root/toolchain.lock.json")"
for workload in form-service realtime-chat rpc-graphql; do
  w="$root/workloads/$workload"
  report="${TMPDIR:-/tmp}/ores-comparisons-${workload}-schema-parity.json"
  npx --yes --package "@oresoftware/typespec-json-schema-validator@$version" tjsv check \
    --typespec="$w/contracts/main.tsp" \
    --schema="$w/contracts/entities.schema.json" \
    --instances="$w/conformance/instances" \
    --report="$report"
  "$root/scripts/generate-workload-contracts.mjs" "$w" --check
done
