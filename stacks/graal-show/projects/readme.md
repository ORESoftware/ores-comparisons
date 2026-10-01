# Graal Show comparison projects

Graal Show is registered in `shared/stack-catalog.json` and reserves the governed comparison branch `stack/graal-show`.

The target execution model is now defined in [`../ARCHITECTURE.md`](../ARCHITECTURE.md): one tenant+deployment OS process as the hard security boundary, with multiplexed `stateless`, `route`, `session`, and opt-in `route_session` Graal isolate affinity inside that process.

This stack is not yet marked `materialized`. Promotion requires stack-native implementations of all six comparison scenarios plus the Graal-specific isolate benchmarks, matching dummy-org branches/gitlinks, build/deploy verification, and benchmark/runtime-proof integration. Native Image and Polyglot Engine isolate results must be reported separately because their code-cache/runtime-memory semantics differ.

Until those artifacts exist, CI must not count Graal Show as executable comparison coverage.
