# Graal Show comparison architecture

Graal Show is compared as a **tenant-process + multiplexed-isolate** FaaS runtime.

## Hard boundary

A worker runs one OS process/cgroup per immutable `tenant_id + deployment_id` generation. The process is the hard inter-tenant address-space/failure boundary.

## Isolate affinity

Inside the tenant process requests may use four affinity modes:

| Mode | Key | Reuse model |
| --- | --- | --- |
| `stateless` | none | pre-warmed bounded isolate pool |
| `route` | `route_id` | reusable isolate per route |
| `session` | `session_id` | reusable isolate per active user/session; 300s default idle TTL |
| `route_session` | `route_id + session_id` | opt-in composite isolate |

`route_session` is never implicit because its cardinality can approach active-users × active-routes.

## Native Image target

For AOT Java/JVM deployments, the comparison model assumes a single tenant Native Image generation with multiple Native Image isolates. The executable/read-only image code is loaded for the process/image; route/session isolates own separate mutable runtime heaps and GC state. Requests are multiplexed onto reusable warm isolates rather than creating a new OS process per request.

The benchmark must report actual image/process RSS and isolate committed/resident memory on the tested GraalVM version. It must not assume a universal 3 MB binary, 256–512 KiB isolate heap, sub-5 ms startup, or sub-microsecond GC unless the fixture measures those values.

## Polyglot target

For JavaScript, Python, and Wasm, each Graal Polyglot `Engine` configured with isolate spawning is an isolated heap/GC/JIT domain. Multiple Contexts on one explicit Engine can share Engine-cached parsed/optimized code; separate Engine instances do not share that Engine-level code cache.

Therefore route/session Engine isolates are supported but high-cardinality session modes must be bounded and reported separately from Native Image isolate density.

## Benchmark dimensions

The Graal Show stack should eventually materialize comparison scenarios for:

1. cold tenant-process start;
2. warm stateless invocation;
3. warm route-isolate hit;
4. warm session-isolate hit;
5. route+session composite hit;
6. isolate creation and teardown latency;
7. five-minute idle eviction/recreation;
8. per-isolate memory and aggregate tenant-process RSS;
9. concurrent Contexts within a route/stateless Polyglot isolate;
10. deployment generation swap + bounded drain with affinity state pinned to the old generation until completion.

All measurements must distinguish Native Image isolates from Polyglot Engine isolates rather than combining them under one generic "Graal isolate" number.
