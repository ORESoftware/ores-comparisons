# Graal Show comparison projects

Graal Show uses the dedicated runtime-fixture path rather than requiring the six BEAM-oriented scenario branches.

| Fixture org | Source → target | Canary | Current proof |
| --- | --- | --- | --- |
| `ores-dummy-org-clojure-jvm-1` | Clojure → JVM | `*-web-server.clj` | source smoke + `gs-compiler` admission command defined |
| `ores-dummy-org-java-jvm-1` | Java → JVM | `*-web-server.java` | `javac` smoke + `gs-compiler` admission command defined |

Both canaries include the Graal Show JVM admission policy and expect `dist/worker.jar` plus compiler admission metadata. Their exact pinned revisions and blockers live in `shared/runtime-execution-evidence.json`.

Graal Show remains `status: registered`: neither lane is marked `runtime_proven` yet. Promotion requires both JVM lanes to produce admitted artifacts, execute through the Graal Show runtime, record receipts, and pass the shared conformance/benchmark gates. Topology alone is not executable coverage.
