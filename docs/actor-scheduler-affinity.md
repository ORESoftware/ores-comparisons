# Actor scheduler affinity

Status: implemented as a scheduler optimization on top of the OresVM four-domain
scheduler.

## Topology

One OresVM owns four independent scheduler domains:

1. `CONTROL` — main/root Oreslang work, supervisors, and ActorMailman control-plane turns.
2. `SHARED_ACTOR` — shared-memory actors.
3. `ISOACTOR` — private/isoactors with confined actor memory.
4. `UNTRUSTED_ACTOR` — untrusted actors in the untrusted execution boundary.

The actor domains remain separate thread pools. Work stealing never crosses a
scheduler-domain boundary.

## Hard constraints

These are correctness rules:

- one actor holds at most one execution lease at a time;
- no two carrier threads may execute one actor concurrently;
- SHARED, ISOACTOR, and UNTRUSTED actors stay in their owning domain;
- CONTROL/root work does not borrow actor carriers;
- await/yield always unwinds the current guest turn before a continuation can run.

Violating one of these rules is a runtime error.

## Soft affinity

Carrier affinity is deliberately weaker than the rules above.

Each actor remembers a preferred carrier thread after its first dispatch. A later
runnable turn is wrapped with that preference and inserted into the same bounded
domain queue. A worker scans a bounded prefix of the queue and prefers:

1. work homed to itself;
2. unbound work or work whose preferred carrier has retired;
3. after a short grace interval, foreign-affine work.

This lets a just-yielded or just-awakened actor return to the carrier whose L1/L2
working set is most likely still warm, without reserving that carrier.

If an actor repeatedly runs on another carrier, the preference is adaptively
re-homed. A dead preferred carrier is also re-homed immediately.

## Why affinity is not a constraint

A hard rule such as "actor A may only run on worker 7" can leave CPU capacity idle
while worker 7 is blocked, over budget, or simply busy. OresVM therefore treats
affinity as a bounded scheduling preference. After the affinity grace interval,
an idle worker may steal the actor turn.

The single-executor lease still serializes the actor, so stealing can change
performance but cannot change program semantics.

## CPU/core pinning

The current Java runtime records carrier-thread affinity, not a portable
source-visible CPU number. The JVM and operating system remain free to migrate a
carrier between logical CPUs.

A platform backend may later pin stable carriers to logical CPUs or NUMA-local
processor sets when supported. Such pinning must remain an optimization: loss of
the hint, unsupported platforms, container CPU-set changes, or NUMA rebalancing
must not affect Oreslang correctness.

## Observability

`ActorRuntime.dispatcherAffinityStats(kind)` exposes:

- `affinityHits` — turns that returned to the preferred carrier;
- `migrations` — turns executed by another carrier;
- `rehomes` — preference changes after repeated migration or carrier retirement.

These counters are scheduler telemetry suitable for runtime diagnostics and
ores-otel export; they are not language semantics.
