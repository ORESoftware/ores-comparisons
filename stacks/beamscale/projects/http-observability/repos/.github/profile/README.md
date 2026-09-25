# BeamScale / http-observability

This is the BeamScale lane for the matched `http-observability` workload. The hosted
Gleam code keeps BeamScale's capability boundary: tenant code does not open raw
sockets, spawn processes or read arbitrary files.

## Contracts and database

The project has peer TypeSpec and JSON Schema authorities under `contracts/`,
differential examples under `conformance/`, and policy under `governance/`.
Reviewed projection metadata generates PostgreSQL SQL + seed data, Protobuf and
Rust/TypeScript/Gleam interfaces.

## Local cluster

`ORESoftware/ores-compose` is pinned to 0.1.0 / `fbfad966…`.

```sh
cd ../../../..
nix develop
just tools-bootstrap
just compose-plan stacks/beamscale/projects/http-observability
just compose-up stacks/beamscale/projects/http-observability
```

Startup waits for PostgreSQL, runs contract conformance, migrates, seeds, then
launches the pinned `bmscl dev` runtime with the pinned supervisor/compiler.
