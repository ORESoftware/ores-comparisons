# ORES Stack / http-observability

This native Rust/Axum lane keeps the real api-docs/ORES Stack build bridge and
adds peer TypeSpec/JSON Schema domain authorities with generated SQL, seed data,
Protobuf and language interfaces.

The pinned `ores-compose` graph starts PostgreSQL, checks contract parity,
migrates+seeds, then launches the exact pinned `ores-stack dev` CLI.

```sh
cd ../../../..
nix develop
just tools-bootstrap
just compose-up stacks/ores-stack/projects/http-observability
```
