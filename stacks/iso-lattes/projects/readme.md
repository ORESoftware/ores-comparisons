# Iso Lattes comparison projects

Iso Lattes uses dedicated JavaScript-target runtime fixtures.

| Fixture org | Source → target | Canary | Current proof |
| --- | --- | --- | --- |
| `ores-dummy-org-gleam-js-1` | Gleam → JavaScript | `*-web-server.gleam` | Gleam JS build/run contract defined |
| `ores-dummy-org-typescript-js-1` | TypeScript → JavaScript | `*-web-server.ts` | pinned TypeScript JS build/smoke contract defined |

TypeScript → WASM is explicitly forbidden; this lane emits JavaScript only. The current Iso Lattes CLI exposes `capabilities`, `functions`, and `invoke`, so the canaries record those actual runtime probes rather than inventing a deploy command.

Iso Lattes remains `status: registered` until both generated JavaScript artifacts are installed through the real runtime deployment/control-plane path and actual `isl invoke` receipts are recorded. Exact revisions and blockers are governed by `shared/runtime-execution-evidence.json`.
