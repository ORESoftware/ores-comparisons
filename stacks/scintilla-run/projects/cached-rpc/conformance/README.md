# Project conformance

This project inherits the repository TypeSpec + JSON Schema parity gate, generated-artifact drift checks, and local database lifecycle contract.

Local startup is admitted through `.ores-compose.yaml`:

1. loopback Postgres starts from the pinned `postgres:16-alpine` image;
2. `db-bootstrap` waits for Postgres, applies generated migrations, then generated seed SQL;
3. `app` starts only after the DB readiness marker is healthy.

Run repository-wide checks from the repository root:

```sh
npm run contracts:parity
npm run contracts:generated-check
npm run contracts:layout
```
