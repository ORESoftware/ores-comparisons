# Scintilla / http-observability

This matched Scintilla workload keeps the authored endpoint-v1 contract and adds
peer TypeSpec/JSON Schema authorities, generated PostgreSQL/Protobuf/language
projections, conformance fixtures and governance.

The local `ores-compose` graph starts PostgreSQL, the pinned Gleam runner, the
pinned Rust backend, applies migration+seed, then runs `scintilla dev`.

```sh
cd ../../../..
nix develop
just tools-bootstrap
just compose-plan stacks/scintilla-run/projects/http-observability
just compose-up stacks/scintilla-run/projects/http-observability
```
