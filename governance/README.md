# Governance

`governance/toolchain.lock.json` is the reviewed toolchain authority for the comparison harness.

Rules:

1. TypeSpec and JSON Schema Draft 2020-12 are independent authored authorities. Neither may overwrite the other.
2. `tjsv` must fail closed on unexplained structural or behavioral drift before generated artifacts are admitted.
3. `generated/**` is read-only to humans. Change `contracts/**`, then run the generator.
4. Every stack project owns a `.ores-compose.yaml` and inherits the root toolchain lock.
5. Local Postgres is loopback-only and disposable. Production credentials never belong in comparison manifests.
6. Database migrations are deterministic projections from the authored JSON Schema plus `storage.sql-map.json`; seed rows come from `seed.instances.json`.
7. Downstream TypeScript, Rust, SQL and Protobuf artifacts are projections admitted only after the two contract authorities converge.
