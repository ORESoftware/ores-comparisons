#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for workload in http-observability forms-chat-workflow cached-rpc; do
  "$root/scripts/admit-and-generate.sh" "$root/workloads/$workload"
done
