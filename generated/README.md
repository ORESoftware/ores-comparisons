# Generated artifacts

Do not hand-edit this directory.

`node scripts/generate-contract-artifacts.mjs` produces deterministic runtime-validation JSON Schema, SQL, seed SQL, TypeScript, Rust and Protobuf projections from:

- `contracts/comparison-domain.schema.json`
- `contracts/storage.sql-map.json`
- `contracts/seed.instances.json`

The seed rows are validated against the authored JSON Schema before SQL is emitted. TypeSpec remains an independent authority: the `tjsv` parity/differential gate must pass before generated output is admitted.
