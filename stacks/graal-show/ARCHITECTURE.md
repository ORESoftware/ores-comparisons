# Graal Show comparison architecture

Graal Show is compared as a **tenant-process + multiplexed-isolate** FaaS runtime.

## Hard boundary

A worker runs one OS process/cgroup per immutable `tenant_id + deployment_id + security_profile` generation. The process/cgroup (or stronger container/microVM boundary) is the hard inter-tenant and cross-privilege boundary.

Authorization level is therefore **not** merely another isolate-affinity key. Public/read-only, authenticated, and administrator profiles use distinct process/credential/network-policy domains so a compromised public isolate cannot inherit admin credentials.

## Isolate affinity

Inside one security-profile process, isolate placement may use one or more declared affinity dimensions:

| Dimension | Key | Typical benefit |
| --- | --- | --- |
| stateless | none | bounded pre-warmed pool, maximum density |
| route | `route_id` | prepared statements, route-local caches, handler-specific warm state |
| session | `session_id` | user/session actor state and cache |
| entity | `entity_id` | aggregate-root/document/channel state and serialized write locality |
| fault domain | `fault_domain_id` | contain brittle third-party/legacy dependency stalls |
| data partition | `data_partition_id` | warm DB/vector-shard connections and partition-local caches |
| composite | declared ordered tuple | opt-in combinations such as route+session or entity+partition |

Composite affinity is explicit and bounded. The scheduler must never automatically cross-product all active dimensions because cardinality can grow multiplicatively.

## Lifecycle policy

Every isolate class carries lifecycle policy rather than relying on one global TTL:

- **max requests** — useful for stateless/route/fault-domain pools; default comparison fixture: 1,000 requests;
- **idle TTL** — useful for session/entity/composite actors; default comparison fixture: 300 seconds;
- **absolute max age** — hard recycling guard for every stateful isolate; default comparison fixture: 1 hour;
- **queue depth** — bounded per affinity key;
- **memory/admission budget** — hard cap on isolate count and aggregate process RSS.

An isolate is recycled when any configured lifecycle limit is reached. Heap state is a cache/working set, never the durable source of truth.

## Native Image target

For AOT Java/JVM deployments, the comparison model assumes a single tenant/security-profile Native Image generation with multiple Native Image isolates. The executable/read-only image code is loaded for the process/image; route/session/entity/partition isolates own separate mutable runtime heaps and GC state. Requests are multiplexed onto reusable warm isolates rather than creating a new OS process per request.

The benchmark must report actual image/process RSS and isolate committed/resident memory on the tested GraalVM version. It must not assume a universal 3 MB binary, 256–512 KiB isolate heap, sub-5 ms startup, or sub-microsecond GC unless the fixture measures those values.

## Polyglot target

For JavaScript, Python, and Wasm, each Graal Polyglot `Engine` configured with isolate spawning is an isolated heap/GC/JIT domain. Multiple Contexts on one explicit Engine can share Engine-cached parsed/optimized code; separate Engine instances do not share that Engine-level code cache.

Therefore high-cardinality session/entity/composite Engine isolates must be bounded and reported separately from Native Image isolate density.

## Benchmark dimensions

The Graal Show stack should eventually materialize comparison scenarios for:

1. cold tenant/security-profile process start;
2. warm stateless invocation;
3. warm route-isolate hit;
4. warm session-isolate hit;
5. warm entity/aggregate-root hit;
6. warm fault-domain pool hit under a deliberately slow dependency;
7. warm data-partition/vector-node hit with connection reuse;
8. route+session and entity+partition composite hits;
9. isolate creation and teardown latency;
10. five-minute idle eviction/recreation;
11. fixed-request-count recycling;
12. one-hour absolute-age recycling;
13. per-isolate memory and aggregate tenant-process RSS;
14. concurrent Contexts within a route/stateless Polyglot isolate;
15. security-profile process separation and credential non-overlap;
16. deployment generation swap + bounded drain with affinity state pinned to the old generation until completion.

All measurements must distinguish Native Image isolates from Polyglot Engine isolates rather than combining them under one generic "Graal isolate" number.
