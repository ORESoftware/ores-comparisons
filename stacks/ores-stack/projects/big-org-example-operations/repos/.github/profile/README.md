# big-org-example-operations — ORES Stack

A native Rust operations/audit service built around the `TypedModule` contract. It requests provider-neutral HTTP, database, KV, queue, and secrets capabilities and leaves actual provider admission to ORES Stack.

OTEL, shared middleware, rate limiting, Redis LRU, forms/sync, chat/conversation context, `api-docs`, and SOPS/age are all declared by the same comparison manifest used by the other stacks. This project emphasizes deterministic server startup and RPC route identity before adding browser-WASM page cost.

```sh
ores-stack check
ores-stack build
cargo run --release --locked
```
