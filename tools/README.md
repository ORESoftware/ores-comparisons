# Pinned local tooling

The machine-readable authority is `tools/toolchain.lock.json`. Tooling is
double-bound by package version where available and by exact Git commit.

Key pins:

- `ores-compose`: 0.1.0 / `26e331f458c9802727802514b380ff49ebc7270a`
- `typespec-json-schema-validator`: 0.1.1 / `e29a91d7ef74e3b0613ea79e988bec4c467535d2`

The local bootstrap also pins BeamScale, Scintilla and ORES Stack CLI/runtime
revisions. Generated projects must not silently use floating `main` branches.
