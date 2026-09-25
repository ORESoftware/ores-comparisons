#!/usr/bin/env bash
set -euo pipefail

stacks=(beamscale scintilla-run ores-stack)
projects=(big-org-example-commerce big-org-example-collaboration big-org-example-operations)
required_integrations=(
  ores-otel ores-forms opto-sync ores-chat ores-convo ores-rate-limit
  ores-middleware ores-redis-lru-cache api-docs ores-sops
)

for stack in "${stacks[@]}"; do
  for project in "${projects[@]}"; do
    root="stacks/$stack/projects/$project"
    test -f "$root/README.md" || { echo "missing $root/README.md" >&2; exit 1; }
    test -f "$root/comparison.toml" || { echo "missing $root/comparison.toml" >&2; exit 1; }
    test -d "$root/env/enc" || { echo "missing $root/env/enc" >&2; exit 1; }
    test -d "$root/env/dec" || { echo "missing $root/env/dec" >&2; exit 1; }
    for dep in "${required_integrations[@]}"; do
      grep -q "$dep" "$root/comparison.toml" || { echo "$root missing integration $dep" >&2; exit 1; }
    done
  done
done

if git ls-files | grep -E '/env/dec/.*\.(env|json|ya?ml|toml)$' >/dev/null; then
  echo "plaintext decrypted environment material is tracked" >&2
  exit 1
fi

echo "comparison layout: ok"
