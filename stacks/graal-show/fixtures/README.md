# Graal Show runtime fixture orgs

These organizations are the stack-specific source → artifact/runtime fixtures governed by `shared/dummy-org-fleet.json`. They are separate from the six cross-stack application scenario orgs in `shared/dummy-org-map.json`.

| Org | Source | Target | Repositories |
| --- | --- | --- | ---: |
| `ores-dummy-org-clojure-jvm-1` | `clojure` | `jvm` | 19 |
| `ores-dummy-org-java-jvm-1` | `java` | `jvm` | 19 |
| `ores-dummy-org-ruby-graal-1` | `ruby` | `graal` | 2 pinned now; 20-repo family governed |

The fixture topology is materialized when the declared repository family exists and its `main` branch is reachable with the comparison read credential. This does **not** by itself promote the stack to executable benchmark coverage; runtime/build proof remains a separate gate.


The Ruby fixture uses **Roda** for normal server mode. Only the API source and lambdas contract are pinned initially; the fleet ledger records it as partial instead of claiming the rest of the 20-repository family is part of the Ruby runtime proof.

The separate Rails reference pair is governed by `shared/graal-ruby-references.json` and is not counted as another dummy org.
