# Local orchestration

Every concrete example has `.ores-compose.yaml` using the current `ores.compose.v1` contract and `.ores-compose.lock.json` carrying the exact CLI revision. Install that revision with `scripts/install-ores-compose.sh`.

The common service graph is `postgres -> app`. Scintilla adds `scintilla-control-plane`, and its app depends on both Postgres and the local control plane. Postgres itself is launched by Docker as an Ores Compose managed host process because the current local `ores-compose up` implementation is host-process oriented; the dependency and health semantics remain owned by Ores Compose.

App startup is fail-closed: contract admission -> generation -> SQL convergence -> migration -> seed -> stack dev command. If any prior phase fails, the dev server is not started.
