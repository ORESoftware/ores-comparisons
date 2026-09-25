# ores-comparisons

Deployable, side-by-side example projects for the three ORES application stacks.

| Stack | Execution model | Projects |
| --- | --- | --- |
| [BeamScale](https://github.com/beamscale) | admitted Gleam -> Erlang/BEAM; one host-supervised actor/process per invocation | `http-observability`, `forms-chat-workflow`, `cached-rpc` |
| [Scintilla](https://github.com/scintilla-run) | polyglot functions in containers/sub-processes | `http-observability`, `forms-chat-workflow`, `cached-rpc` |
| [ORES Stack](https://github.com/ores-stack) | Rust native servers + WASM/page assets + RPC/API generation | `http-observability`, `forms-chat-workflow`, `cached-rpc` |

Every project uses the same comparison contract and declares adapters for `ores-otel`, `ores-forms`, `opto-sync`, `ores-chat`, `ores-convo`, `ores-rate-limit`, `ORESoftware/ores-middleware`, `ores-redis-lru-cache`, `ORESoftware/api-docs`, `ORESoftware/ores-sops`, and the `ores-sops` organization target.

## Layout

```text
stacks/
  beamscale/projects/{http-observability,forms-chat-workflow,cached-rpc}
  scintilla-run/projects/{http-observability,forms-chat-workflow,cached-rpc}
  ores-stack/projects/{http-observability,forms-chat-workflow,cached-rpc}
workloads/
  http-observability/{contracts,conformance,governance,db,generated}
  forms-chat-workflow/{contracts,conformance,governance,db,generated}
  cached-rpc/{contracts,conformance,governance,db,generated}
shared/
  integrations.json
```

Each concrete project retains its stack-native source/deployment files from the original examples and additionally contains `contracts/`, `conformance/`, `governance/`, `db/`, `generated/`, `.ores-compose.yaml`, and `.ores-compose.lock.json` bindings to the canonical workload contract.

## Peer-authority contracts

TypeSpec and authored JSON Schema Draft 2020-12 are independent peer authorities. `@oresoftware/typespec-json-schema-validator` (`tjsv`) compiles the TypeSpec authority with its pinned official JSON Schema emitter, compares the two structural authorities, validates recorded/synthesized instances through both lanes, and emits the TypeSpec witness plus digest-bound Contract IR.

Generation is deliberately split after admission:

- **TypeSpec lane:** official `tjsv` JSON Schema witness -> Protobuf + SQL.
- **JSON Schema lane:** authored Draft 2020-12 schema -> Rust interfaces + TypeScript interfaces + executable validation schema + SQL.
- **Cross-authority gate:** the two independently generated SQL projections must converge after stripping only their provenance comment. Only then is `generated/sql/001_schema.sql` eligible for migration.

`storage.manifest.json` is the reviewed relational mapping for Postgres. `projection.lock.json` freezes Protobuf field numbers and enum ordinals. Generated outputs are ignored by Git except their README; edit the authorities, never the generated files.

Run the full contract gate with:

```sh
nix develop
just conformance
```

## Local Ores Compose clusters

The current Ores Compose project contract is `.ores-compose.yaml` / `ores.compose.v1`, so the examples use that canonical name rather than inventing a TOML dialect. The CLI is pinned to immutable Git revision `fbfad966f9770a9a8d3895880523280324b4ddc6` (crate `0.1.0`) in `toolchain.lock.json` and repeated in every project’s `.ores-compose.lock.json`.

```sh
nix develop
just compose-install

just up stacks/beamscale/projects/http-observability
just up stacks/scintilla-run/projects/forms-chat-workflow
just up stacks/ores-stack/projects/cached-rpc
```

Every local cluster starts a `postgres:16-alpine` development database on loopback port `55432` by default. Ores Compose waits for Postgres health; app startup then runs `tjsv`, regenerates both projection lanes, requires SQL convergence, applies the generated migration with `psql -v ON_ERROR_STOP=1`, applies the workload’s idempotent dev seed, and only then starts `bmscl dev`, `scintilla dev`, or `ores-stack dev`.

Scintilla projects additionally start the pinned `scintilla-run/scintilla-backend.rs` revision from `toolchain.lock.json` on `127.0.0.1:8091` and wait for `/healthz` before `scintilla dev` synchronizes endpoints.

BeamScale’s local database preparation remains trusted orchestration outside tenant actors; the Hosted Gleam worker keeps its no-ambient-filesystem/process/network/secret/database capability boundary.

## Secrets

Every project keeps the existing per-project `.sops.yaml`, `.env.example`, `env/enc/`, and `env/dec/` layout. `scripts/bootstrap-env.sh` creates encrypted environment files from public age recipients; decrypted data and private identities are never committed.

```sh
export DEV_AGE_RECIPIENT=age1...
export STAGE_AGE_RECIPIENT=age1...
export PROD_AGE_RECIPIENT=age1...
export RECOVERY_AGE_RECIPIENT=age1...
just env-init stacks/beamscale/projects/http-observability
```

## Verification

The original example verifier remains available:

```sh
just verify
```

The stronger contract/local-orchestration gate is:

```sh
./scripts/check-layout.sh
./scripts/check-project-bindings.sh
./scripts/check-contracts.sh
```

The first keeps the 3x3 example matrix complete, the second enforces shared contract bindings and exact Ores Compose pins, and the third performs TypeSpec/JSON Schema differential conformance, Contract IR/witness generation, Protobuf lock enforcement, dual SQL generation, and SQL convergence.
