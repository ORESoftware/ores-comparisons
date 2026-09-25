# Governance

`ores-comparisons` treats TypeSpec source and JSON Schema Draft 2020-12 source as independent peer authorities. Neither is generated from the other. `ORESoftware/typespec-json-schema-validator` must converge them before any SQL, Rust, TypeScript, validation-schema copy, or Protobuf projection is trusted.

The three stacks intentionally consume the same workload contract packs. A stack-specific example may change execution/runtime mechanics, but it may not silently redefine the workload entity model.

Generation is dual-lane. The official TypeSpec JSON Schema witness produced by `tjsv` drives Protobuf and an independent SQL projection. The authored JSON Schema drives Rust/TypeScript validation interfaces and a second SQL projection. Promotion fails unless the two SQL projections converge after removing only their provenance comment. This makes SQL an explicit cross-authority parity surface rather than a projection from one preferred source.

Generated artifacts are downstream evidence. Edit `workloads/<name>/contracts/`, not `generated/`. Protobuf field numbers and enum ordinals are locked in `projection.lock.json`; incompatible reuse is a governance failure. Database migrations consume only the admitted converged SQL artifact plus the explicit relational mapping in `storage.manifest.json`.

Local orchestration is governed by `.ores-compose.yaml` and the immutable CLI revision in `toolchain.lock.json` / each project's `.ores-compose.lock.json`. Plaintext secrets remain outside Git; local Postgres credentials are development-only non-secret defaults.
