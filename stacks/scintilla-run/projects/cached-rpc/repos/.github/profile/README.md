# Scintilla / cached-rpc

This Scintilla comparison project uses the same governed data contract as the
other stacks and generates PostgreSQL, Protobuf and language interfaces from
reviewed projection metadata after TypeSpec↔JSON Schema conformance.

```sh
cd ../../../..
nix develop
just tools-bootstrap
just compose-up stacks/scintilla-run/projects/cached-rpc
```

The local graph starts PostgreSQL plus the exact pinned Scintilla runner/backend
before `scintilla dev`.
