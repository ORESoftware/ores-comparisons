#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected_rev="$(jq -r '.ores_compose.rev' "$root/toolchain.lock.json")"
for stack in beamscale scintilla-run ores-stack; do
  for workload in form-service realtime-chat rpc-graphql; do
    p="$root/stacks/$stack/projects/$workload"
    test "$(jq -r '.workload' "$p/contracts/contract-set.json")" = "$workload"
    test "$(jq -r '.ores_compose.rev' "$p/.ores-compose.lock.json")" = "$expected_rev"
    test -f "$p/.ores-compose.yaml"
    test -d "$p/contracts" -a -d "$p/conformance" -a -d "$p/governance"
  done
done
echo 'project bindings ok'
