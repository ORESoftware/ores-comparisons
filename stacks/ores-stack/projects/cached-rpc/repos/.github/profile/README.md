# ORES Stack / cached-rpc

This native Rust/Axum lane uses the same governed cached-RPC domain contract as
BeamScale and Scintilla. TypeSpec and JSON Schema are peer authorities; SQL,
seed data, Protobuf and language interfaces are generated downstream.

```sh
cd ../../../..
nix develop
just tools-bootstrap
just compose-up stacks/ores-stack/projects/cached-rpc
```

The local graph starts PostgreSQL, checks conformance, migrates and seeds, then
launches the pinned `ores-stack dev` CLI on 127.0.0.1:3112.
