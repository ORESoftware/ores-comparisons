# ores-comparisons

Runnable, contract-first examples for comparing the BeamScale, Scintilla Run, and ORES Stack execution models.

The repository keeps the workloads intentionally matched so performance, cost, cold-start behavior, isolation, observability, deployment ergonomics, and failure semantics can be compared without changing the application goal.

## Workload matrix

| workload | purpose | shared integration focus |
| --- | --- | --- |
| `form-service` | public form render/submit and durable sync handoff | `ores-forms`, `opto-sync`, middleware, rate limiting |
| `realtime-chat` | message publish/history shape and sync/cache boundaries | `ores-chat`, `ores-convo`, `opto-sync`, Redis LRU |
| `rpc-graphql` | contract-driven RPC + GraphQL surface | `api-docs`, middleware, telemetry, cache/rate limiting |

Each workload exists under all three stacks:

```text
stacks/{beamscale,scintilla-run,ores-stack}/projects/{form-service,realtime-chat,rpc-graphql}
```

## Cross-stack integration authorities

All examples consume the same registry in `integrations/registry.toml` and the same environment names. The integration set includes `ores-otel`, `ores-forms`, `opto-sync`, `ores-chat`, `ores-convo`, `ores-rate-limit`, `ORESoftware/ores-middleware`, `ores-redis-lru-cache`, `ORESoftware/api-docs`, `ORESoftware/ores-sops`, and the `ores-sops` org family.

No example commits decrypted secrets. Every project has `env/enc/` for SOPS+age ciphertext and `env/dec/` for local ephemeral plaintext. `env/dec/**` is ignored globally. Use `nix develop` and `scripts/env.sh` to encrypt/decrypt.

## Stack intent

- **BeamScale**: compiler-admitted Gleam/Erlang actor lambdas. Tenant code stays capability-bound; secrets and external integration credentials stay in trusted host/control-plane layers.
- **Scintilla Run**: container/subprocess endpoints discovered through `.scintilla-endpoint.toml` and deployed with the canonical `scintilla` CLI.
- **ORES Stack**: Rust/WASM-first standalone servers with deterministic `api-docs` route/RPC contracts and room for generated RPC/GraphQL clients.

## Local prerequisites

```sh
nix develop
./scripts/check-layout.sh
```

Install the stack CLI being exercised (`bmscl`, `scintilla`, or `ores-stack`) from its owning repository or through the normal zed-pkg flow.

## Secret workflow

```sh
export SOPS_AGE_RECIPIENTS='age1...'
export SOPS_AGE_KEY_FILE="$HOME/.config/sops/age/keys.txt"

# Encrypt env/dec/dev.yaml -> env/enc/dev.sops.yaml
./scripts/env.sh encrypt stacks/beamscale/projects/form-service dev

# Decrypt env/enc/dev.sops.yaml -> env/dec/dev.yaml
./scripts/env.sh decrypt stacks/beamscale/projects/form-service dev
```

Never commit `env/dec/*` or raw credentials.
