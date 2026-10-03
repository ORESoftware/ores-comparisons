# GitHub organization manifest governance

TypeSpec and JSON Schema Draft 2020-12 are peer authorities for the local
organization-manifest shape. Each project records its concrete repository graph
at `repos/.github/org.manifest.json`.

Repository dependency edges must resolve to declared sibling repositories.
Generated repositories identify the shared authority they derive from.
