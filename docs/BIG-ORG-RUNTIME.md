# Big-org local runtime topology

The big-org comparison projects model a GitHub organization and a local runtime
as separate governed graphs.

The organization graph lives in repos/.github/org.manifest.json. It describes
repository ownership and cross-repository dependencies.

The local runtime graph lives in repos/.github/runtime/topology.json. It selects
the sibling repos that become local processes and records their logical startup
dependencies. TypeSpec and JSON Schema peer authorities for this document live
under shared/runtime-topology-contract.

The .ores-compose.yaml file is generated from the runtime topology. It adds
platform infrastructure without changing the sibling-repository DAG:

1. PostgreSQL starts and crosses pg_isready.
2. db-bootstrap runs contract checks, migrations, and seed projections and then
   holds a readiness marker.
3. Scintilla additionally starts its pinned runner and backend.
4. service, worker, and frontend sibling repos start in dependency waves.
5. the aggregate app repo starts only after all scenario repos are ready.

Because ores-compose deliberately requires traversal-free working directories,
the .github infra repository does not set working_dir to ../repo. Instead it
calls scripts/run-sibling.py with an admitted service name. That launcher reads
runtime/topology.json, rejects undeclared or traversal-shaped names, resolves
the sibling directory, and only then execs the stack-native CLI.

BeamScale sibling repos run bmscl dev. Scintilla sibling repos run scintilla dev
against the local runner/backend control plane. ORES Stack sibling repos run
ores-stack dev with deterministic loopback BIND_ADDR values and independent
browser-reload sidecar ports, allowing their /healthz endpoints to participate
in compose readiness.

Regenerate or check the projection with:

    python3 scripts/generate_big_org_compose.py
    python3 scripts/generate_big_org_compose.py --check

The dedicated big-org-runtime workflow validates the authored topology, checks
org-manifest parity, rejects dependency cycles and endpoint collisions, and
runs the exact-pinned ores-compose parser/planner against all nine clusters.
