#!/usr/bin/env bash
set -euo pipefail

stacks=(beamscale scintilla-run ores-stack)
projects=(big-org-example-commerce big-org-example-collaboration big-org-example-operations)
ids=(
  ores_otel ores_forms opto_sync ores_chat ores_convo ores_rate_limit
  ores_middleware ores_redis_lru_cache api_docs ores_sops_legacy ores_sops_org
)

fail() { echo "big-org verification FAILED: $*" >&2; exit 1; }

[[ -f contracts/big-org-example.tsp ]] || fail "missing TypeSpec authority"
[[ -f contracts/big-org-example.schema.json ]] || fail "missing JSON Schema authority"
jq -e . contracts/big-org-example.schema.json >/dev/null || fail "invalid JSON Schema JSON"
[[ -f shared/integrations.json ]] || fail "missing repository integration authority"

for id in "${ids[@]}"; do
  grep -q "\"id\"[[:space:]]*:[[:space:]]*\"$id\"" shared/integrations.json \
    || fail "shared/integrations.json missing $id"
done

for stack in "${stacks[@]}"; do
  [[ ! -e "stacks/$stack/repos" ]] || fail "stacks/$stack/repos is forbidden; repos/ belongs inside each project"

  for project in "${projects[@]}"; do
    root="stacks/$stack/projects/$project"
    [[ -d "$root" ]] || fail "missing $root"
    for rel in README.md comparison.toml .sops.yaml .env.example env/enc/README.md env/dec/.gitignore repos/readme.md; do
      [[ -f "$root/$rel" ]] || fail "$root missing $rel"
    done

    grep -q "^stack = \"$stack\"$" "$root/comparison.toml" || fail "$root stack identity mismatch"
    grep -q "^scenario = \"$project\"$" "$root/comparison.toml" || fail "$root scenario identity mismatch"
    for id in "${ids[@]}"; do
      grep -q "\"$id\"" "$root/comparison.toml" || fail "$root missing integration $id"
    done

    for rule in '^env/enc/dev\.env\.enc$' '^env/enc/stage\.env\.enc$' '^env/enc/prod\.env\.enc$'; do
      grep -Fq "$rule" "$root/.sops.yaml" || fail "$root missing SOPS rule $rule"
    done

    tracked_dec=$(git ls-files "$root/env/dec" | grep -v '/\.gitignore$' || true)
    [[ -z "$tracked_dec" ]] || fail "$root tracks decrypted env material: $tracked_dec"

    while IFS=$'\t' read -r meta path; do
      mode=${meta%% *}
      [[ "$mode" != "160000" ]] || [[ "$path" == "$root/repos/"* ]] \
        || fail "submodule $path for $root is outside project-owned repos/"
    done < <(git ls-files --stage "$root")

    case "$stack" in
      beamscale)
        [[ -f "$root/.ores-lambda.toml" ]] || fail "$root missing .ores-lambda.toml"
        [[ -f "$root/bmscl-policy.toml" ]] || fail "$root missing bmscl-policy.toml"
        [[ -f "$root/lambdas/work/gleam.toml" ]] || fail "$root missing Gleam lambda package"
        [[ -f "$root/lambdas/work/src/main.gleam" ]] || fail "$root missing Gleam entrypoint"
        grep -q '^filesystem = "deny"$' "$root/.ores-lambda.toml" || fail "$root does not deny filesystem"
        grep -q '^raw_sockets = "deny"$' "$root/.ores-lambda.toml" || fail "$root does not deny raw sockets"
        ;;
      scintilla-run)
        endpoint="$root/endpoints/work/.scintilla-endpoint.toml"
        entrypoint="$root/endpoints/work/lambda.mjs"
        [[ -f "$endpoint" && -f "$entrypoint" ]] || fail "$root missing Scintilla endpoint"
        grep -q '^containerized = true$' "$endpoint" || fail "$root endpoint is not containerized"
        grep -q '^runtime = "nodejs"$' "$endpoint" || fail "$root endpoint runtime drift"
        node --check "$entrypoint" >/dev/null || fail "$root Node entrypoint syntax invalid"
        grep -q 'invocationId: request.invocationId' "$entrypoint" || fail "$root does not echo invocationId"
        ;;
      ores-stack)
        for rel in .ores-stack.toml Cargo.toml Cargo.lock src/main.rs contracts/service.route-map.json; do
          [[ -f "$root/$rel" ]] || fail "$root missing $rel"
        done
        jq -e '.schema_version == "1.0.0" and .map.RunWork == "/v1/work"' \
          "$root/contracts/service.route-map.json" >/dev/null || fail "$root invalid api-docs route map"
        grep -q 'ores-stack-pub-lib-core' "$root/Cargo.toml" || fail "$root missing typed module SDK"
        grep -q 'assert_module::<WorkModule>()' "$root/src/main.rs" || fail "$root missing compile-time module assertion"
        grep -q 'graphql_projection' "$root/comparison.toml" || fail "$root missing GraphQL projection declaration"
        ;;
    esac
  done
done

if find stacks -type d -path '*/projects/example*' -print -quit | grep -q .; then
  fail "example* project prefix found; organization-scale projects must use big-org-example-*"
fi

if git grep -nE 'AGE-SECRET-KEY-1[0-9A-Z]{40,}' -- . ':!*.md' >/dev/null 2>&1; then
  fail "private age identity appears to be committed"
fi

echo "big-org verification OK: 3 stacks x 3 projects, project-owned repos/, 11 integrations each"
