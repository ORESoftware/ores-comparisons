# Graal Show comparison projects

Graal Show is registered in `shared/stack-catalog.json` and reserves the governed comparison branch `stack/graal-show`.

The target execution model is now defined in [`../ARCHITECTURE.md`](../ARCHITECTURE.md): one tenant+deployment OS process as the hard security boundary, with multiplexed `stateless`, `route`, `session`, and opt-in `route_session` Graal isolate affinity inside that process.

This stack is not yet marked `materialized`. Promotion requires stack-native implementations of all six comparison scenarios plus the Graal-specific isolate benchmarks, matching dummy-org branches/gitlinks, build/deploy verification, and benchmark/runtime-proof integration. Native Image and Polyglot Engine isolate results must be reported separately because their code-cache/runtime-memory semantics differ.

Until those artifacts exist, CI must not count Graal Show as executable comparison coverage.


## Ruby web app → Graal lambda lane

Graal Show now has a framework-neutral Ruby lowering path modeled on the ORES Stack standalone→lambda split:

- the app repository owns route discovery and emits a static `generated/graal/manifest.json`;
- `gs ruby-lambdas` stages each route/group unit and invokes `gs-compiler`;
- `gs-compiler` sanitizes/admits the generated Ruby and emits one immutable TruffleRuby worker artifact per unit;
- the Roda dummy fixture and Rails demo are both imported into this comparison surface.

The dummy lane uses Roda so Rails behavior is not accidentally treated as the Graal contract. The Rails demo remains the richer reference for Rails conventions, physical route handlers, and the Graal supervisor.
