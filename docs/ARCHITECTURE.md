# Architecture

The repository compares **execution models**, not feature sets.

## Shared logical request path

```text
request
  -> ores-middleware / ores-rate-limit
  -> stack-native handler
  -> ores-forms / opto-sync / ores-chat / ores-convo as required
  -> ores-redis-lru-cache when cacheable
  -> api-docs operation identity
  -> ores-otel telemetry
```

Secrets are activated before the runtime starts:

```text
Git: env/enc/*.env.enc
  -> SOPS + age
  -> local/runtime-only env/dec/*.env
  -> .env symlink or platform env injection
```

BeamScale is the deliberate exception to ambient environment/network access:
tenant code receives only admitted host capabilities. Its integration endpoints
must be surfaced through trusted host/platform adapters or admitted same-cluster
routes; user Gleam code does not open sockets or read arbitrary files.

Scintilla resolves only the environment variable names declared in
`.scintilla-endpoint.toml::env_from`; values never enter build manifests.

ORES Stack is a native Rust build. API route identity remains owned by
`ORESoftware/api-docs`, while `ores-stack` orchestrates page/RPC build,
browser WASM finalization, and server execution.
