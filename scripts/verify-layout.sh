#!/usr/bin/env bash
set -euo pipefail

stacks=(beamscale scintilla-run ores-stack)
projects=(big-org-example-commerce big-org-example-collaboration big-org-example-operations)
required_integrations=(
  ores-otel ores-forms opto-sync ores-chat ores-convo ores-rate-limit
  ores-middleware ores-redis-lru-cache api-docs ores-sops
)

for dep in "${required_integrations[@]}"; do
  grep -q "$dep" integrations.toml || { echo "integration manifest missing $dep" >&2; exit 1; }
done
grep -q 'ORESoftware/ores-sops' integrations.toml || { echo "missing legacy ores-sops integration" >&2; exit 1; }
grep -q 'github.com/ores-sops' integrations.toml || { echo "missing ores-sops org integration" >&2; exit 1; }

for stack in "${stacks[@]}"; do
  test -f "stacks/$stack/env/enc/README.md" || { echo "missing encrypted env docs for $stack" >&2; exit 1; }
  test -f "stacks/$stack/env/dec/README.md" || { echo "missing decrypted env docs for $stack" >&2; exit 1; }
  test -f "stacks/$stack/repos/README.md" || { echo "missing source-checkout slot for $stack" >&2; exit 1; }

  for project in "${projects[@]}"; do
    root="stacks/$stack/projects/$project"
    test -f "$root/README.md" || { echo "missing $root/README.md" >&2; exit 1; }
    test -f "$root/comparison.toml" || { echo "missing $root/comparison.toml" >&2; exit 1; }
    grep -q '../../../../integrations.toml' "$root/comparison.toml" || { echo "$root does not bind shared integrations" >&2; exit 1; }
    for feature in otel forms sync chat convo rate_limit middleware redis_lru rpc_docs sops_age; do
      grep -q "^${feature} = true$" "$root/comparison.toml" || { echo "$root feature $feature is not enabled" >&2; exit 1; }
    done

    case "$stack" in
      beamscale)
        test -f "$root/lambdas/work/gleam.toml"
        test -f "$root/lambdas/work/src/main.gleam"
        ;;
      scintilla-run)
        test -f "$root/endpoints/work/.scintilla-endpoint.toml"
        test -f "$root/endpoints/work/lambda.mjs"
        grep -q '^containerized = true$' "$root/endpoints/work/.scintilla-endpoint.toml"
        ;;
      ores-stack)
        test -f "$root/.ores-stack.toml"
        test -f "$root/Cargo.toml"
        test -f "$root/Cargo.lock"
        test -f "$root/src/main.rs"
        test -f "$root/contracts/service.route-map.json"
        ;;
    esac
  done
done

if git ls-files | grep -E '/env/dec/.*\.(env|json|ya?ml|toml)$' >/dev/null; then
  echo "plaintext decrypted environment material is tracked" >&2
  exit 1
fi

if find stacks -type d -path '*/projects/example-*' | grep -q .; then
  echo "project names must use the big-org-example prefix, never example-*" >&2
  exit 1
fi

echo "comparison layout: ok"
