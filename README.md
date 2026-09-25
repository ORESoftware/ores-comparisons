# ores-comparisons

Runnable, contract-first examples for comparing the BeamScale, Scintilla Run, and ORES Stack execution models.

The repository keeps workloads intentionally matched so performance, cost, cold-start behavior, isolation, observability, deployment ergonomics, persistence, and failure semantics can be compared without changing the application goal.

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

The canonical data contracts live under `workloads/<workload>/`. Every concrete stack project has `contracts/`, `conformance/`, and `governance/` bindings to that same workload authority so a comparison cannot silently change its entity model.

## Contract and generation model

TypeSpec and JSON Schema Draft 2020-12 are independent peer authorities. `@oresoftware/typespec-json-schema-validator` fails closed when they disagree. After admission, `scripts/generate-workload-contracts.mjs` produces downstream SQL, Rust interfaces, TypeScript interfaces, a JSON Schema validation projection, Protobuf, and a deterministic digest manifest.

`storage.manifest.json` supplies the reviewed relational mapping for Postgres. `projection.lock.json` fixes Protobuf field numbers and enum ordinals. Generated outputs are ignored by Git and must never be hand-edited.

## Local Ores Compose cluster

Every project owns a `.ores-compose.yaml` at the project root. Ores Compose itself is pinned by immutable Git revision in `toolchain.lock.json` and repeated in each `.ores-compose.lock.json`.

```sh
nix develop
./scripts/install-ores-compose.sh

# Example: Postgres -> migrate/seed -> BeamScale dev
./scripts/up-project.sh stacks/beamscale/projects/form-service

# Same shape for the other stacks/workloads
./scripts/up-project.sh stacks/scintilla-run/projects/realtime-chat
./scripts/up-project.sh stacks/ores-stack/projects/rpc-graphql
```

All projects start a local `postgres:16-alpine` service on loopback port `55432` by default. The app startup wrapper regenerates SQL, applies the schema with `psql -v ON_ERROR_STOP=1`, applies an idempotent seed, then starts `bmscl dev`, `scintilla dev`, or `ores-stack dev`. Override the database port with `ORES_COMPARE_PG_PORT`.

Scintilla projects also launch a pinned local `scintilla-backend.rs` control plane on `127.0.0.1:8091` and wait for `/healthz` before `scintilla dev` synchronizes endpoints.

## Cross-stack integration authorities

All examples consume the registry in `integrations/registry.toml`. The integration set includes `ores-otel`, `ores-forms`, `opto-sync`, `ores-chat`, `ores-convo`, `ores-rate-limit`, `ORESoftware/ores-middleware`, `ores-redis-lru-cache`, `ORESoftware/api-docs`, `ORESoftware/ores-sops`, and the `ores-sops` org family.

## Secrets

No example commits decrypted secrets. Every project has `env/enc/` for SOPS+age ciphertext and `env/dec/` for local ephemeral plaintext. `env/dec/**` is ignored globally. Use `nix develop` and `scripts/env.sh` to encrypt/decrypt.

BeamScale tenant actors intentionally do not receive ambient database/secret authority; database migration is a trusted local orchestration step outside the actor runtime.

## Conformance

```sh
./scripts/check-layout.sh
./scripts/check-project-bindings.sh
./scripts/check-contracts.sh
```

The contract gate executes both TypeSpec and JSON Schema over recorded instances through `tjsv`, then generates the downstream projections and immediately re-checks deterministic output.
