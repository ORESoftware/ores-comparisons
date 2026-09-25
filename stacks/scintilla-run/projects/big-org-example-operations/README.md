# big-org-example-operations — Scintilla

An operations/audit workload built around containerized subprocesses. Scintilla owns the process lifecycle and timeout boundary; the lambda returns one sealed response per invocation and keeps diagnostics on stderr.

The common integration manifest enables OTEL, forms/sync, chat/conversation context, shared middleware, rate limiting, Redis LRU caching, `api-docs`, and SOPS/age without changing the portable invocation envelope.

```sh
scintilla build --project . --out-dir .scintilla
scintilla deploy --project . --out-dir .scintilla --dry-run
scintilla deploy --project . --out-dir .scintilla
```
