#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

for project in stacks/beamscale/projects/big-org-example-*; do
  for repo in "$project"/repos/{catalog-service,orders-worker,storefront-web,presence-service,message-worker,workspace-web,ingest-service,automation-worker,ops-console}; do
    [[ -f "$repo/repo.contract.json" ]] || continue
    echo "==> BeamScale $repo"
    (
      cd "$repo"
      "$ROOT/.local/bin/bmscl" build .         --out-dir ./dist         --policy ./bmscl-policy.toml         --worker-config ./.ores-lambda.toml
    )
  done
done

for project in stacks/scintilla-run/projects/big-org-example-*; do
  for repo in "$project"/repos/{catalog-service,orders-worker,storefront-web,presence-service,message-worker,workspace-web,ingest-service,automation-worker,ops-console}; do
    [[ -f "$repo/repo.contract.json" ]] || continue
    echo "==> Scintilla $repo"
    (
      cd "$repo"
      "$ROOT/.local/bin/scintilla" build         --project .         --out-dir .scintilla         --check
    )
  done
done

for project in stacks/ores-stack/projects/big-org-example-*; do
  for repo in "$project"/repos/{catalog-service,orders-worker,storefront-web,presence-service,message-worker,workspace-web,ingest-service,automation-worker,ops-console}; do
    [[ -f "$repo/repo.contract.json" ]] || continue
    echo "==> ORES Stack $repo"
    (
      cd "$repo"
      "$ROOT/.local/bin/ores-stack" check
      "$ROOT/.local/bin/ores-stack" build
    )
  done
done
