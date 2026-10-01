# Iso Lattes runtime fixture orgs

These organizations are the stack-specific source → JavaScript fixtures governed by `shared/dummy-org-fleet.json`.

| Org | Source | Target | Repositories |
| --- | --- | --- | ---: |
| `ores-dummy-org-gleam-js-1` | `gleam` | `js` | 19 |
| `ores-dummy-org-typescript-js-1` | `typescript` | `js` | 19 |

TypeScript remains a JavaScript target lane here; it is not modeled as TypeScript → WASM. Runtime/build proof remains separate from topology materialization.
