# ORES Stack / forms-chat-workflow

This native Rust/Axum lane uses the same governed forms/chat domain contract as
BeamScale and Scintilla. TypeSpec and JSON Schema are peer authorities; SQL,
seed data, Protobuf and language interfaces are generated downstream.

```sh
cd ../../../..
nix develop
just tools-bootstrap
just compose-up stacks/ores-stack/projects/forms-chat-workflow
```

The local graph starts PostgreSQL, checks conformance, migrates and seeds, then
launches the pinned `ores-stack dev` CLI on 127.0.0.1:3111.
