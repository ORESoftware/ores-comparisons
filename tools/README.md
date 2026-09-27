# Pinned local tooling

The machine-readable authority is `tools/toolchain.lock.json`. Tooling is
double-bound by package version where available and by exact Git commit.

Key pins:

- `ores-compose`: 0.1.0 / `4e790084f09bc5e69a91859e86810070897ec6f9`
  - exact source head from `ORESoftware/ores-compose#212`;
  - funded exact-head evidence proves mixed host + OCI execution, one-shot bootstrap barriers,
    `compose_ready`, clean SIGINT teardown, and no leaked matching OCI containers.
- `typespec-json-schema-validator`: 0.1.1 / `e29a91d7ef74e3b0613ea79e988bec4c467535d2`

The local bootstrap also pins BeamScale, Scintilla and ORES Stack CLI/runtime
revisions. Generated projects must not silently use floating `main` branches.
