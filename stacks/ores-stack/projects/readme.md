# ORES Stack comparison projects

These are native Rust/Axum projects driven by `ores-stack` and the canonical
`ORESoftware/api-docs` page/route contract. Each project uses the actual
api-docs Cargo build bridge and generated `ORES_PAGES_RS` router.

- `http-observability` — middleware/telemetry-focused native server.
- `forms-chat-workflow` — forms/sync/chat/conversation surface.
- `cached-rpc` — api-docs RPC identity + Redis/LRU cache port.

Use `ores-stack check`, `ores-stack routes`, `ores-stack build`, and
`ores-stack dev`.
