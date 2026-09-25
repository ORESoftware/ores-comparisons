#!/usr/bin/env bash
set -euo pipefail
stacks=(beamscale scintilla-run ores-stack)
projects=(http-observability forms-chat-workflow cached-rpc)
for stack in "${stacks[@]}"; do
  for project in "${projects[@]}"; do
    root="stacks/$stack/projects/$project"
    test -f "$root/README.md"
    test -f "$root/comparison.toml"
    test -f "$root/.ores-compose.yaml"
    test -f "$root/.ores-compose.lock.json"
    test -f "$root/contracts/contract-set.json"
    test -f "$root/conformance/conformance-set.json"
    test -f "$root/governance/README.md"
    test -f "$root/db/README.md"
    test -f "$root/generated/README.md"
    test -d "$root/env/enc"
    test -d "$root/env/dec"
  done
done
for workload in "${projects[@]}"; do
  root="workloads/$workload"
  test -f "$root/contracts/main.tsp"
  test -f "$root/contracts/entities.schema.json"
  test -f "$root/contracts/storage.manifest.json"
  test -f "$root/contracts/projection.lock.json"
  test -d "$root/conformance/instances"
  test -f "$root/governance/README.md"
  test -f "$root/db/seeds/dev.sql"
done
echo "layout ok: 3 stacks x 3 merged projects + 3 shared workload authorities"
