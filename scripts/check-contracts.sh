#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for workload in http-observability forms-chat-workflow cached-rpc; do
  w="$root/workloads/$workload"
  "$root/scripts/admit-and-generate.sh" "$w"
  witness="$w/generated/typespec-witness/typespec.generated.schema.json"
  "$root/scripts/generate-workload-contracts.mjs" "$w" --typespec-witness="$witness" --check
  test -s "$w/generated/sql/001_schema.sql"
  cmp <(tail -n +2 "$w/generated/sql/from-typespec.sql") <(tail -n +2 "$w/generated/sql/from-json-schema.sql")
  test "$(jq -r '.sql_convergence.equal' "$w/generated/manifest.json")" = true
  test -s "$w/generated/rust/entities.rs"
  test -s "$w/generated/typescript/entities.ts"
  test -s "$w/generated/protobuf/entities.proto"
  test -s "$w/generated/json-schema/entities.schema.json"
  test -s "$w/generated/tjsv-report.json"
  test -s "$w/generated/contract-ir.json"
done
