# ORES Stack / http-observability

Native Rust/Axum request path with ORES middleware and telemetry ports.

This uses the actual `ORESoftware/api-docs` page build bridge: `build.rs`
writes the generated page router to `ORES_PAGES_RS`, and `src/main.rs`
includes that router into an Axum server. The pinned `ores-middleware` package
is present at the application boundary; the integration contract records the
other service ports.

The canonical stack has moved toward https://github.com/ores-stack; the example
only relies on the `ores-stack` CLI command and the public api-docs/middleware
contract repos, so the repository move does not change project layout.

## Run

```sh
ores-stack check
ores-stack routes
ores-stack build
ores-stack dev
```

For a quick Rust-only smoke test before invoking the orchestrator:

```sh
cargo check
```
