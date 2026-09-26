# Runtime topology governance

TypeSpec and JSON Schema Draft 2020-12 are independently authored peer
authorities for the big-org local runtime topology.

Each big-org simulated organization stores authored runtime input at
repos/.github/runtime/topology.json. The corresponding .ores-compose.yaml is a
deterministic projection, not an independent authority.

The projection may add platform infrastructure such as PostgreSQL, the
Scintilla runner/backend, and a database bootstrap barrier, but it must preserve
the authored sibling-repository dependency graph.
