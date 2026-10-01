# ORES Stack runtime fixture orgs

ORES Stack keeps its existing six-scenario Rust comparison coverage and also has two dedicated source → artifact fixtures governed by `shared/dummy-org-fleet.json`.

| Org | Source | Target | Repositories |
| --- | --- | --- | ---: |
| `ores-dummy-org-rust-native-1` | `rust` | `native` | 19 |
| `ores-dummy-org-rust-wasm-3` | `rust` | `wasm` | 19 |

These fixtures exercise native and WASM artifact paths without changing the existing six-scenario gitlink authority.
