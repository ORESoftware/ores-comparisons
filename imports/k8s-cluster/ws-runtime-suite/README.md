# k8s-cluster WebSocket/runtime comparison extraction

Source snapshot: `ORESoftware/k8s-cluster` commit `cc675fd772d56e917d6b19e03a9c62e98d02248d`.

This directory preserves the best comparison-oriented pieces from three embedded runtime services while leaving their original source and Kubernetes deployments untouched:

- `akka-ws-server` — Akka Streams vs native async Java pipeline benchmark.
- `fsharp-ws-server` — Rx.NET vs native F# task pipeline benchmark using the same timing shape.
- `gleamlang-ws-server` — Erlang-process-per-WebSocket runtime with an explicit apples-to-apples benchmark fast path.

These are reference fixtures for `ores-comparisons`, not production deployment copies. Kubernetes manifests, DD-specific ingress, secrets, and cluster wiring remain in `k8s-cluster`.

## Common comparison contract

The extracted suite should converge on one workload envelope and measurement vocabulary across languages:

```json
{"id":"case-123","payload":"hello"}
```

For microbenchmarks record at minimum:

- warmup iteration count;
- measured iteration count;
- payload bytes;
- p50 / p95 / p99 latency in microseconds;
- mean/min/max where the runtime exposes them;
- wall time;
- requests/second.

For load tests keep the microbenchmark distinct from concurrent WebSocket load. The source Akka and F# implementations intentionally benchmark sequential per-call overhead; concurrent throughput belongs to the shared WebSocket load-test lane.

## Fairness rules borrowed from the source implementations

1. Run the same payload and operation graph in every implementation.
2. Warm each implementation before measurement so JIT/class loading is not mislabeled as steady-state runtime cost.
3. Do not compare a full product path against a synthetic echo fast path unless both expose the same contract.
4. Report runtime/version, CPU/memory limits, replica count, transport, concurrency, and payload size with every result.
5. Keep telemetry and health endpoints outside measured request latency.
6. Preserve failures and cause chains rather than silently discarding failed iterations.
7. Run repeated trials and retain raw machine-readable samples; do not publish only a single aggregate number.

## Source provenance

- Akka benchmark: `remote/deployments/akka-ws-server/src/main/java/com/oresoftware/dd/akkaws/bench/BenchmarkRunner.java`, blob `2d9b97003e84190232888b791c1f13b5e1bf9132`.
- F# benchmark: `remote/deployments/fsharp-ws-server/BenchmarkRunner.fs`, blob `62450b8f313ddcfc49c4f9c7be81def0fd00993c`.
- Gleam connection/benchmark fast path: `remote/deployments/gleamlang-ws-server/src/gleamlang_ws_server/connection.gleam`, blob `a522353ea821ff42eb1b35548651c2d0bdcfb04f`.

The destination fixtures are deliberately isolated under `imports/` until a normalized comparison project consumes them.
