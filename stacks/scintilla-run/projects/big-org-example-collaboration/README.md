# big-org-example-collaboration — Scintilla

A container/subprocess collaboration service using Scintilla's sealed stdio invocation envelope. The long-lived process can serve repeated form/sync/chat operations while Scintilla owns timeout, recycling, isolation, and deployment revisions.

`ores-forms`, `opto-sync`, `ores-chat`, and `ores-convo` are the workload-facing integrations; middleware, rate limiting, Redis LRU, OTEL, API contract generation, and SOPS/age are applied through the shared comparison contract.

```sh
scintilla build --project . --out-dir .scintilla
scintilla deploy --project . --out-dir .scintilla --dry-run
scintilla deploy --project . --out-dir .scintilla
```
