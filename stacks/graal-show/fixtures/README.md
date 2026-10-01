# Graal Show runtime fixture orgs

These organizations are the stack-specific source → artifact/runtime fixtures governed by `shared/dummy-org-fleet.json`. They are separate from the six cross-stack application scenario orgs in `shared/dummy-org-map.json`.

| Org | Source | Target | Repositories |
| --- | --- | --- | ---: |
| `ores-dummy-org-clojure-jvm-1` | `clojure` | `jvm` | 19 |
| `ores-dummy-org-java-jvm-1` | `java` | `jvm` | 19 |

The fixture topology is materialized when the declared repository family exists and its `main` branch is reachable with the comparison read credential. This does **not** by itself promote the stack to executable benchmark coverage; runtime/build proof remains a separate gate.
