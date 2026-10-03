# Integration matrix

All 18 matrix-governed projects declare the full integration set in
`repos/.github/comparison.toml`. The table below describes the three benchmark scenarios; the three larger
organization examples per stack carry the same integration authority while
exercising broader multi-repo flows. Ports not used on the hot path remain wired
and testable so projects do not silently drift apart.

| Integration | HTTP/telemetry | Forms/chat workflow | Cached RPC |
| --- | --- | --- | --- |
| ores-otel | hot path | hot path | hot path |
| ores-forms | ready | hot path | ready |
| opto-sync | ready | hot path | ready |
| ores-chat | ready | hot path | ready |
| ores-convo | ready | hot path | ready |
| ores-rate-limit | hot path | hot path | hot path |
| ores-middleware | hot path | hot path | hot path |
| ores-redis-lru-cache | ready | ready | hot path |
| api-docs | hot path | hot path | hot path |
| ORESoftware/ores-sops | activation | activation | activation |
| ores-sops | org alias / activation target | org alias / activation target | org alias / activation target |

"Ready" means the project carries the port/config contract but the representative
request does not have to call it. This is intentional: comparison traffic should
not pay unrelated network cost.
