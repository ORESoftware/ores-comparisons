# ores-comparisons

Deployable, side-by-side example projects for BeamScale, Scintilla, and ORES
Stack. Each stack implements the same three workloads:

- `http-observability`
- `forms-chat-workflow`
- `cached-rpc`

| Stack | Execution model |
| --- | --- |
| BeamScale | admitted Gleam -> Erlang/BEAM actor/lambda execution |
| Scintilla | polyglot lambda/container/sub-process runtime |
| ORES Stack | Rust native servers, WASM/page assets, RPC/API generation |

All nine projects expose the same integration graph: ores-otel, ores-forms,
opto-sync, ores-chat, ores-convo, ores-rate-limit, ores-middleware,
ores-redis-lru-cache, api-docs, and both ORES SOPS organization paths.

## Contract structure

Every project contains:

```text
contracts/
  typespec/main.tsp
  json-schema/domain.schema.json
  projection.json
  generated/
    validation/domain.schema.json
    sql/{001_init,002_seed}.sql
    protobuf/comparison.proto
    interfaces/{typescript.ts,rust.rs,gleam.gleam}
conformance/
  instances/
  check.sh
governance/
  authority-contract.json
  README.md
```

TypeSpec and JSON Schema Draft 2020-12 are **peer authorities**. Neither is
generated from or silently replaces the other.
`ORESoftware/typespec-json-schema-validator` compares them fail-closed and
executes the recorded valid/invalid corpus. Only after that gate do reviewed
projection declarations produce SQL, seed data, Protobuf and language
interfaces. Generated outputs are committed so drift is visible in review.

## Reproducible local clusters

`tools/toolchain.lock.json` pins `ores-compose`, contract tooling, stack CLIs
and local runtimes to exact Git revisions. The Nix shell supplies PostgreSQL 16
and the language toolchains.

```sh
nix develop
just tools-bootstrap
just verify

just compose-plan stacks/beamscale/projects/http-observability
just compose-up stacks/beamscale/projects/http-observability
```

Every `.ores-compose.yaml` includes PostgreSQL as a supervised host process.
The current pinned `ores-compose` executor intentionally runs host processes
only, so the examples do not pretend OCI execution is available. Startup waits
for `pg_isready`, checks contracts, runs the generated idempotent migration and
seed scripts, then launches the stack-native dev runtime.

Scintilla additionally launches its exact-pinned Gleam runner and Rust backend.
BeamScale points its CLI at the exact-pinned supervisor/compiler. ORES Stack
runs the exact-pinned `ores-stack` CLI.

## Secrets

Each project preserves the SOPS + age boundary:

- `env/enc/` — committed ciphertext only;
- `env/dec/` — runtime-only plaintext, ignored by Git;
- `.sops.yaml` — exact dev/stage/prod recipient rules;
- `.env.example` — non-secret local defaults.

Use `just env-init <project-path>` after supplying public age recipients. No
age private key and no decrypted environment file belongs in Git.

See `docs/ARCHITECTURE.md` for the authority and startup model.


## CI authority levels

Default CI has no implicit permission to clone sibling private repositories.
Therefore the always-on gate validates structure, generated drift, a restricted
TypeSpec/JSON Schema parity model, and valid/invalid fixture behavior entirely
from this checkout.

The full authority remains
`ORESoftware/typespec-json-schema-validator@e29a91d...`, and the full compose
parser remains `ORESoftware/ores-compose@fbfad966...`. When repository secret
`ORES_REPO_READ_TOKEN` is configured with read-only access to those repos, CI
also checks every project through those exact pinned implementations. Local
`just tools-bootstrap` does the same using the developer's existing Git
credentials; it does not depend on an unpublished npm package.


## Smoke tests and performance matrix

`benchmarks/matrix.json` covers all nine stack/scenario combinations with
argv-only build/deploy smoke commands. BeamScale and Scintilla use their real
dry-run deployment flags; ORES Stack app repos explicitly stop at artifact
handoff until an infra target is supplied.

Benchmark observations and smoke receipts have their own TypeSpec + JSON Schema
peer authorities under `benchmarks/contracts/`. The runner records warm
p50/p95/p99 latency plus optional cold-start time, RSS, artifact size, and
estimated cost per million requests.

Use `just smoke-check`, `just smoke-execute`, `just benchmark ...`, and
`just benchmark-matrix`.
