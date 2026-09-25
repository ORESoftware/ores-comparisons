# Contract architecture

Each logical workload is authored once under `workloads/<workload>/contracts` and consumed by all three runtime implementations.

## Authorities

- `main.tsp` — independently authored TypeSpec authority.
- `entities.schema.json` — independently authored JSON Schema Draft 2020-12 authority.
- `storage.manifest.json` — explicit reviewed relational mapping for persisted models.
- `projection.lock.json` — wire-compatibility lock for Protobuf field numbers and enum ordinals.

`tjsv` must admit the TypeSpec/JSON Schema pair before generation. The generated TypeSpec witness is evidence only and is never copied back over the authored JSON Schema.

## Projection lanes

TypeSpec witness -> Protobuf and SQL. Authored JSON Schema -> Rust, TypeScript, executable JSON Schema and SQL. The two SQL lanes must converge before the migration artifact is admitted.

This arrangement deliberately keeps two contract authorities independent while still detecting semantic drift at the SQL boundary used by Postgres.

## Database startup

Each project points to its workload with `contracts/contract-set.json`. `scripts/local-migrate.sh` resolves that binding, runs admission/generation, applies `generated/sql/001_schema.sql`, then runs `db/seeds/dev.sql`. Ores Compose makes that wrapper an app-start prerequisite after Postgres is healthy.
