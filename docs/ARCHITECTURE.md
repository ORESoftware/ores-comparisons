# Architecture

The repository compares execution models while holding workload semantics,
database entities and contract authority rules constant.

## Contract admission chain

```text
independently authored TypeSpec ──┐
                                  ├─ tjsv parity + differential corpus ─┐
independently authored JSON Schema┘                                    │
                                                                       v
                                                    reviewed projection metadata
                                                                       |
                           +-------------------+-----------------------+----------------+
                           v                   v                       v                v
                    PostgreSQL SQL/seed    Protobuf              language types   validation schema
```

Generated artifacts are downstream projections, never a new authority. A
contract change is admissible only when both peer authorities converge and the
valid/invalid corpus agrees with them.

## Local runtime graph

Every project is launched through the exact-pinned
`ORESoftware/ores-compose` v1 contract:

```text
PostgreSQL
    |
    +--> contract parity/drift gate
             |
             +--> generated migration
                     |
                     +--> generated seed
                             |
                             +--> stack-native dev runtime
```

Scintilla inserts its real local runner/backend before the endpoint watcher.
BeamScale resolves its pinned supervisor/compiler through the local toolchain
lock. ORES Stack launches its native Rust dev server.

The pinned ores-compose `up` executor executes host processes today. PostgreSQL
therefore comes from Nix `postgresql_16`; these manifests deliberately do not
claim unsupported container execution.

## Database lifecycle

- Local data is confined to each project's ignored `.local/postgres/data`.
- `initdb` uses trust authentication only for the loopback local-development
  cluster.
- `pg_isready` is the dependency readiness boundary.
- Migration SQL uses `CREATE TABLE IF NOT EXISTS`.
- Seed SQL uses `ON CONFLICT DO NOTHING`.
- The projection generator rejects unknown tables/columns, duplicate columns,
  unapproved SQL types, missing primary keys, and invalid protobuf references.
- Destructive schema evolution requires a new reviewed migration version rather
  than editing state in place.

## Shared integrations

The logical request path remains:

```text
request
  -> ores-middleware / ores-rate-limit
  -> stack-native handler
  -> ores-forms / opto-sync / ores-chat / ores-convo
  -> ores-redis-lru-cache when cacheable
  -> api-docs operation identity
  -> ores-otel telemetry
```

BeamScale tenant code receives admitted capabilities instead of ambient network
or filesystem authority. Scintilla endpoint configs declare env references, not
secret values. ORES Stack retains api-docs as the route/RPC identity authority.


## Project vs repository boundary

A comparison **project** is deliberately a multi-repository envelope:

```text
projects/<scenario>/
  .ores-compose.yaml
  contracts/
  conformance/
  governance/
  env/
  scripts/
  repos/
    README.md
    app/
    <future-api-repo>/
    <future-web-repo>/
    ...
```

The project root owns orchestration and cross-repository authorities. Stack-specific source,
build files, endpoint manifests, Cargo manifests, and lambda definitions must live inside a
child of `repos/`; they must not be flattened into the project root. CI fails if those
stack-native files escape `repos/app/`.
