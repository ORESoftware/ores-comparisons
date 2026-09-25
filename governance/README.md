# Governance

`ores-comparisons` treats TypeSpec source and JSON Schema Draft 2020-12 source as independent peer authorities. Neither is generated from the other. `ORESoftware/typespec-json-schema-validator` must converge them before any SQL, Rust, TypeScript, validation-schema copy, or Protobuf projection is trusted.

The three stacks intentionally consume the same workload contract packs. A stack-specific example may change execution/runtime mechanics, but it may not silently redefine the workload entity model.

Generated artifacts are downstream evidence. Edit `workloads/<name>/contracts/`, not `generated/`. Protobuf field numbers and enum ordinals are locked in `projection.lock.json`; incompatible reuse is a governance failure. Database migrations are generated from the admitted JSON Schema plus the explicit relational mapping in `storage.manifest.json`.

Local orchestration is governed by `.ores-compose.yaml` and the immutable CLI revision in `toolchain.lock.json` / each project's `.ores-compose.lock.json`. Plaintext secrets remain outside Git; local Postgres credentials are development-only non-secret defaults.
