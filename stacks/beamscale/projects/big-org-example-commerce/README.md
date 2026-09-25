# big-org-example-commerce — BeamScale

A multi-tenant quote/order/customer service implemented as hosted Erlang/BEAM actor lambdas.

The P3 worker uses the audited `bmscl` SDK. External systems are reached through admitted cluster calls into trusted P2 middleware: `ores-middleware` supplies the injection boundary, `ores-rate-limit` and `ores-redis-lru-cache` guard hot paths, `ores-otel` exports traces, `ores-forms` supplies quote/order forms, `opto-sync` synchronizes clients, and `ores-chat`/`ores-convo` attach customer conversations. `api-docs` and the repository TypeSpec/JSON Schema pair define the wire contract. Secrets are decrypted from the stack-level `env/enc/` store only at execution time.

```sh
bmscl check .
bmscl build . --out-dir dist
bmscl deploy dist --project big-org-example-commerce --environment dev --dry-run
bmscl deploy dist --project big-org-example-commerce --environment dev
```

BeamScale intentionally does not give tenant code arbitrary filesystem/process/network capability; the integration list in `comparison.toml` is therefore an admission input for the trusted P2 adapter layer rather than a request to import unrestricted client SDKs into P3.
