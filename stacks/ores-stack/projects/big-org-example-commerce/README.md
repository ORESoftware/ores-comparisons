# big-org-example-commerce — ORES Stack

A native Rust HTTP service using the migrated `ores-stack-pub-lib-core` typed-module contract. The module requests explicit HTTP/database/KV/queue/secrets capabilities; deployment admission decides which providers satisfy them. This keeps the same user code portable across standalone server and lambda-oriented providers.

`api-docs` route identity lives in `contracts/service.route-map.json`; the repository-level TypeSpec and JSON Schema pair provide the cross-stack request/response model. Shared ORES middleware should inject approved dependencies into callbacks instead of requiring application code to import ambient clients. The integration manifest covers OTEL, forms, sync, chat/convo, rate limits, Redis LRU, API docs, and SOPS/age.

```sh
ores-stack check
ores-stack build
cargo run --release --locked
```
