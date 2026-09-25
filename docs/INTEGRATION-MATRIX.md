# Integration matrix

Every one of the nine projects declares all integration IDs in
`comparison.toml`. The scenario determines which ports are exercised on the
hot path; the others remain wired and testable so comparison projects do not
silently drift apart.

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
