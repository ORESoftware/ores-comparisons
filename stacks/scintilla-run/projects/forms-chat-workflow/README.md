# Scintilla / forms-chat-workflow

This matched workload adds peer TypeSpec/JSON Schema authorities, generated SQL,
seed data, Protobuf and language interfaces, plus local PostgreSQL.

The pinned `ores-compose` graph starts PostgreSQL, the exact Scintilla runner
and backend revisions, migrates/seeds the domain tables, then launches
`scintilla dev --project .`.

```sh
cd ../../../..
nix develop
just tools-bootstrap
just compose-up stacks/scintilla-run/projects/forms-chat-workflow
```
