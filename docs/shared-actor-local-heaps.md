# Shared actors and actor-local heaps

Oreslang separates three concerns that are often conflated:

1. **Scheduling domain** — which bounded carrier pool executes an actor turn.
2. **Allocation domain** — which actor owns ordinary state and allocations.
3. **Sharing capability** — whether the actor may intentionally access runtime-wide shared state.

A `shared actor` is therefore allowed to use explicit shared memory, but its ordinary
actor-owned allocations do not need to belong to one process-global heap.

## Current JVM contract

Every actor receives an `ActorMemorySlice`, exposed as
`ActorContext.localMemory()`.

- PRIVATE / `isoactor`: `localMemory()` and the compatibility
  `privateMemory()` view refer to the same actor-owned domain.
- SHARED: `localMemory()` is present while `privateMemory()` remains empty.
- `Shared<T>`, `SharedMutex<T>`, `SyncCell<T>`, and other intentionally shared
  runtime objects remain in the explicit shared accounting domain.

The JVM implementation currently enforces ownership and quota accounting rather than
claiming a physically independent JVM heap. Native/polyglot backends can map the same
semantic allocation domain to a dedicated arena, slab set, or isolate heap.

## Reference direction

The target heap topology is intentionally asymmetric:

- actor-local -> explicit shared: allowed;
- explicit shared -> actor-local: forbidden except opaque/weak actor identity handles;
- actor A local -> actor B local: forbidden;
- ordinary mutable cross-actor transport: copy, move/take, or receiver-side reconstruction;
- immutable promoted values: may live in the explicit shared domain and be referenced by
  multiple actors.

These rules keep actor-local reclamation independent and prevent local heaps from
degenerating into a cross-heap tracing graph.

## Accounting

The runtime reports these categories independently:

- `privateMemoryBytes()` — actor-local bytes owned by PRIVATE actors;
- `sharedActorLocalMemoryBytes()` — actor-local bytes owned by SHARED actors;
- `actorLocalMemoryBytes()` — sum of both actor-local categories;
- `sharedMemoryBytes()` — explicitly shared runtime state;
- `actorMemoryBytes()` — total actor-local plus explicitly shared bytes.

All categories compete for the parent `IsolatePolicy.maxHeapBytes()` ceiling. Actor
termination closes its local domain and releases actor-local accounting without reclaiming
unrelated explicit shared state.

## Next backend steps

The semantic boundary is now available for progressively stronger implementations:

- per-turn bump/scratch arenas for non-escaping temporaries;
- per-actor slab/page ownership for persistent state;
- optional ActorGroup-local heaps for chatty actor clusters;
- process-level superheap/page reuse rather than one OS mapping per actor;
- native arena-backed local heaps;
- actor-local collection initiated by `actor.gc()`;
- pressure-driven collection of the heaviest actor domains before process-wide fallback.

The scheduler remains independent: actors may migrate across carrier threads while their
allocation-domain identity stays stable.
