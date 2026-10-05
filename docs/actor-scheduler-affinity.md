# Actor scheduler affinity

Status: implemented as a scheduler optimization on top of the OresVM four-domain
scheduler.

## Topology

One OresVM owns four independent scheduler domains:

1. `CONTROL` — main/root Oreslang work, supervisors, and ActorMailman control-plane turns.
2. `SHARED_ACTOR` — shared-memory actors.
3. `ISOACTOR` — private/isoactors with confined actor memory.
4. `UNTRUSTED_ACTOR` — untrusted actors in the untrusted execution boundary.

The actor domains remain separate CPU thread pools. Work stealing never crosses
a scheduler-domain boundary.

## Hard constraints

These are correctness rules:

- one actor holds at most one execution lease at a time;
- no two carrier threads may execute one actor concurrently;
- SHARED, ISOACTOR, and UNTRUSTED actors stay in their owning domain;
- CONTROL/root work does not borrow actor carriers;
- await/yield always unwinds the current guest turn before a continuation can run.

Violating one of these rules is a runtime error.

## Soft affinity with bounded fairness

Carrier affinity is deliberately weaker than the rules above.

Each actor stores a compact preferred-carrier token after its first dispatch. A
later runnable turn is inserted into the same bounded domain queue. A worker may
prefer young work homed to itself, but affinity is never permitted to defeat
fairness:

1. an unbound/first-run head item wins immediately;
2. a stale carrier-generation preference wins immediately and is stealable;
3. a foreign-affine head may be bypassed only during a tiny grace interval;
4. once that interval expires, FIFO age wins;
5. another worker may then steal the turn.

This keeps a hot actor from repeatedly jumping ahead of an older peer, including
in a one-carrier domain. The actor's existing single-executor lease still
provides the serialization invariant.

Repeated migration adaptively re-homes the actor. A retired carrier token is
recognized as stale through its generation and cannot become accidentally valid
when the physical carrier slot is reused.

## Data-oriented scheduler metadata

Affinity metadata follows the same Data-Oriented Programming principles used by
Oreslang compute execution.

The scheduler does **not** retain a preferred `Thread` object graph for each
actor. Each actor stores primitive hot metadata:

- preferred carrier token: `long`;
- enqueue timestamp: `long`;
- short migration streak: integer state.

Each actor-domain dispatcher owns a dense, bounded `AtomicLongArray` carrier
registry. A token contains a slot plus generation, so carrier lifecycle checks
are contiguous array reads instead of pointer chasing. The `ActorCell` itself
is the queued runnable, eliminating the previous per-turn affinity-wrapper
allocation.

This is an implementation layout choice, not a source-level promise. A future
backend may replace the Java queue with a ring/chunk/deque implementation while
preserving the same scheduling contract.

## CPU versus GPU

Actor mailbox turns are control/state-machine work and remain CPU scheduled.
They are not GPU kernels.

GPU execution belongs to Oreslang's data-oriented compute model:

- actors/classes provide identity, lifecycle, supervision, and orchestration;
- `Region<T>` / `RegionView<T>` provide dense homogeneous data;
- `compute fnc` exposes statically analyzable read/write/discard/reduce/atomic effects;
- CPU backends may choose SoA/AoSoA, SIMD, multicore partitioning, and NUMA-local placement;
- GPU backends may choose tiled/SoA device layouts, kernel launch geometry, residency, and transfer batching;
- `place auto` may choose CPU or GPU from work size, residency, transfer cost, queue pressure, and variant availability.

An actor may initiate an analyzable compute operation and later suspend on its
completion, but the actor itself stays in its CPU actor scheduler domain. A GPU
completion only makes the owning actor continuation runnable; it never executes
actor code on a GPU callback/driver thread.

This separation matters for correctness: CPU/GPU mapping may change physical
layout or processor placement, but it cannot weaken actor isolation, region
ownership, effect ordering, or the one-actor/one-executor rule.

## CPU/core and NUMA pinning

The current Java runtime records logical carrier-token affinity, not a
source-visible CPU number. The JVM and operating system remain free to migrate a
carrier between logical CPUs.

A native/runtime backend may strengthen physical locality by mapping stable
carrier slots to CPU sets or NUMA nodes. That remains an optimization:
unsupported platforms, container CPU-set changes, topology changes, or scheduler
rebalance must not affect Oreslang semantics.

For data-parallel compute, locality policy is richer than actor affinity. CPU
compute may choose NUMA-local chunks and SIMD widths, while GPU compute may
choose device-local physical instances. Those choices belong to the
heterogeneous task/data planner, not the actor mailbox dispatcher.

## Observability

`ActorRuntime.dispatcherAffinityStats(kind)` exposes:

- `affinityHits` — turns that returned to the preferred carrier;
- `migrations` — turns executed by another carrier;
- `rehomes` — preference changes after repeated migration or carrier retirement.

These counters are scheduler telemetry suitable for runtime diagnostics and
ores-otel export; they are not language semantics.
