# big-org-example-commerce — Scintilla

A containerized subprocess implementation of the same quote/order/customer workload. The endpoint implements Scintilla's `stdio-json-v1` envelope so the runtime can keep or recycle the process independently of the application protocol.

The process receives only references/configuration through the environment. `ores-middleware` composes request policy, `ores-rate-limit` and `ores-redis-lru-cache` protect shared services, `ores-otel` propagates `traceparent`, `ores-forms` drives quote/order input, `opto-sync` manages offline state, and chat/conversation services are accessed through their approved adapters. `api-docs` and the shared TypeSpec/JSON Schema pair remain cross-stack contract authorities.

```sh
scintilla build --project . --out-dir .scintilla
scintilla deploy --project . --out-dir .scintilla --dry-run
scintilla deploy --project . --out-dir .scintilla
```
