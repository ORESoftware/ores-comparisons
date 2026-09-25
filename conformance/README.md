# Conformance

Conformance has three layers:

1. TypeSpec ↔ JSON Schema parity and differential instance validation through `tjsv`.
2. Projection admission: SQL/interface/Protobuf generation must consume only an admitted workload contract plus its locked projection metadata.
3. Project binding: every stack/project pair must bind to the canonical workload contract, compose pin, and migration/seed path.

Run `./scripts/check-contracts.sh`, `./scripts/check-project-bindings.sh`, and `./scripts/check-layout.sh` from the repository root.
