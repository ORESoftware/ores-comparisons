#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected_rev="$(jq -r '.ores_compose.rev' "$root/toolchain.lock.json")"
bin="${ORES_COMPOSE_BIN:-$root/.tools/ores-compose/bin/ores-compose}"
for stack in beamscale scintilla-run ores-stack; do
  for workload in form-service realtime-chat rpc-graphql; do
    p="$root/stacks/$stack/projects/$workload"
    binding="$p/contracts/contract-set.json"
    test "$(jq -r '.workload' "$binding")" = "$workload"
    test "$(jq -r '.ores_compose.rev' "$p/.ores-compose.lock.json")" = "$expected_rev"
    for key in typespec json_schema storage projection_lock; do
      rel="$(jq -r ".$key" "$binding")"
      test -f "$p/$rel"
    done
    grep -q '^schema_version: ores.compose.v1$' "$p/.ores-compose.yaml"
    grep -q '^  postgres:$' "$p/.ores-compose.yaml"
    grep -q '^  app:$' "$p/.ores-compose.yaml"
    if [[ "$stack" == scintilla-run ]]; then
      grep -q '^  scintilla-control-plane:$' "$p/.ores-compose.yaml"
    fi
    if [[ -x "$bin" ]]; then
      (cd "$p" && "$bin" --skip-zed-pkg --skip-rpc-gen check .ores-compose.yaml)
    fi
  done
done
echo 'project bindings ok'
