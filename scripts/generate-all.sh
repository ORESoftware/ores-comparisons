#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for workload in form-service realtime-chat rpc-graphql; do
  "$root/scripts/generate-workload-contracts.mjs" "$root/workloads/$workload" "$@"
done
