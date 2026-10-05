# Data-oriented compute, dataflow, and static aspects

Oreslang supports object-oriented/domain modeling and data-oriented execution as
complementary layers.

The semantic rule is:

> logical type and logical ownership are independent of physical data layout and
> processor placement.

A class, actor, or domain object may own or expose a `Region<T>`, while a
compute backend may represent that region as AoS, SoA, AoSoA, tiled device
memory, NUMA-local chunks, or another validated physical instance. Changing the
physical representation must not change the Oreslang-visible value model,
ownership rules, effect contract, or actor isolation.

This document defines the v0 data-oriented/AOP contract. The Java/Truffle
reference interpreter is allowed to execute accepted compute code sequentially
on CPU. SIMD/GPU execution is an optimization/backend choice and must fail
closed when no compatible lowering exists.

## Regions and views

`Region<T>` is the logical homogeneous-data abstraction used by compute
functions. `RegionView<T>` is a zero-copy logical projection over a region or
slice.

Region identity is opaque. Guest code cannot manufacture a colliding identity
or forge a disjointness proof. A physical device pointer/address is never part
of the source-level identity.

A backend may hold several physical instances of the same logical region, for
example:

- host SoA;
- GPU-resident tiled memory;
- a read-only replica on another NUMA node.

Coherence and transfer planning remain runtime/compiler responsibilities.

## Compute functions

A compute function is declared with contextual `compute fnc`:

```ores
compute fnc integrate(Region<Particle> particles, f32 dt, int n): void
reads particles.position, particles.velocity;
writes particles.position;
discards particles.scratch;
reduces particles.mass by sum;
atomic particles.counter;
layout particles soa;
place auto;
{
  parallel simd for int i = 0; i < n; i++ {
    // statically analyzable transformation
  }
  return;
}
```

The words `compute`, `reads`, `writes`, `discards`, `reduces`,
`atomic`, `layout`, `place`, `parallel`, `simd`, `flow`, and
`aspect` are contextual syntax rather than globally reserved identifiers.

### Effects

Effect clauses are compiler-visible logical footprints:

- `reads p.field`: reads the field/region without mutation;
- `writes p.field`: may read and write the field/region;
- `discards p.field`: completely overwrites the prior value; the backend need
  not transfer the old physical contents merely to preserve them;
- `reduces p.field by op`: updates through a named reduction contract;
- `atomic p.field`: requests controlled atomic access.

A target root must name a `Region<T>` or `RegionView<T>` parameter. A bare
parameter name denotes the whole region/view. A dotted path denotes a
compiler-validated field footprint.

The current non-interference floor is conservative:

- read/read over an aliasing footprint may overlap;
- accesses to independently allocated roots do not alias;
- certified-disjoint sibling slices may overlap;
- disjoint fields of the same region may overlap;
- any overlapping write/discard/reduce/atomic access is ordered.

Parallel reduction relaxation is intentionally not guessed. It requires a
separately validated associative/commutative reduction contract.

### Layout

`layout` describes physical layout preference/requirements without changing
logical type identity:

```ores
layout particles auto;
layout particles aos;
layout particles soa;
layout particles aosoa;
layout particles tiled;
```

The runtime planner has first-class layout metadata for:

- AUTO;
- AoS;
- SoA;
- AoSoA with a backend block size;
- tiled layouts with backend tile dimensions.

The initial source syntax names the layout family. Exact vector width/tile shape
may be chosen by a backend from target-machine information. A future explicit
shape syntax can refine that choice without changing `Region<T>` semantics.

### Placement

```ores
place auto;
place cpu;
place gpu;
```

Placement is a constraint/optimization decision, never an authority grant.

AUTO may consider:

- work-item count;
- CPU/GPU variant availability;
- current residency;
- bytes requiring transfer/writeback;
- launch overhead;
- queue pressure;
- memory capacity;
- NUMA/device locality.

When the runtime knows the physical working-set size, GPU admission also checks
that the working set fits the device memory budget. AUTO falls back to an
eligible CPU variant when it does not fit. Explicit GPU placement fails closed
rather than silently oversubscribing device memory.

Explicit GPU placement also fails closed when no GPU backend/eligible variant exists.

## CPU chunk scheduling

Data-oriented CPU execution is scheduled at **chunk/tile granularity**, never as
one scheduler task per entity. The runtime planner can derive a
`CpuChunkPlan` from work-item count, available CPU parallelism, SIMD width, and
a minimum useful chunk size.

Chunk boundaries are rounded to SIMD width where practical. This keeps dense
SoA/AoSoA traversals vector-friendly while bounding scheduler overhead. The last
chunk may contain a scalar/vector tail.

A CPU backend may additionally prefer a carrier, NUMA node, processor group, or
cache domain for a chunk whose physical region instance is already local there.
That locality is a scheduling preference only. If the preferred worker is busy,
bounded stealing is allowed; effects and region dependencies—not affinity—define
correctness.

## Parallel and SIMD loops

Inside a compute function:

```ores
parallel for item of values {
  // independent iteration candidate
}

simd for int i = 0; i < n; i++ {
  // vectorization candidate
}

parallel simd for int i = 0; i < n; i++ {
  // multicore + SIMD candidate
}
```

These are optimization/execution contracts, not permission to introduce a data
race. The front end preserves the requested mode; a backend must still prove
that its lowering respects region/effect/ownership rules. The reference
interpreter may execute the loop sequentially while preserving semantics.

Using a parallel/SIMD loop marker outside `compute fnc` is a compile-time
error.

## Compute-safe subset

The initial compute subset is intentionally conservative and AOT/device
oriented. The compiler also proves that every direct region read/write is
covered by the declared effect footprint and that effects required by nested
compute calls are a subset of the caller's effects. It rejects:

- actor entry points;
- async compute functions and `await`;
- ambient `stdio`, `process`, or `actor` capabilities;
- `new` allocation;
- heap-backed object/list literals;
- closures/lambdas;
- `try/catch/finally` and `defer`;
- arbitrary dynamic/member calls;
- ambient/global data reads or writes that are not explicit parameters/regions;
- unbounded `loop` and conditionless `for`;
- recursive compute call graphs.

A compute function may call another statically resolved compute function or a
known pure numeric intrinsic. This restriction is a semantic safety floor; a
backend/compiler can add verified pure intrinsics without weakening it.

Compute functions remain finite data transformations. Actors remain the
stateful identity/lifecycle/supervision abstraction.

## GPU batching and actor interaction

GPU kernels are not actor turns and do not execute on SHARED/ISOACTOR/UNTRUSTED
actor carrier pools. A heterogeneous backend should batch compatible work by
kernel/variant, device, physical layout, region residency, and transfer/writeback
requirements.

Kernel launch is asynchronous from Oreslang's scheduler perspective. An actor
that initiates GPU compute receives/awaits a Future; `await` releases the actor
execution lease and carrier. Device completion only settles the Future and
re-enqueues the continuation onto the actor's owning scheduler domain.

This avoids occupying actor carriers during device execution and preserves the
same single-executor actor semantics whether the compute variant runs on scalar
CPU, SIMD CPU, multicore CPU, or GPU.

## Dataflow declarations

A flow makes ordering visible to the compiler:

```ores
define flow simulation as
  integrate -> collide -> constraints -> render;
end
```

Each stage must resolve to a compute function. The declaration must contain at
least one edge, must be acyclic, and may not repeat the same canonical edge.

The explicit graph supplies required ordering, but effect analysis may add
additional edges when region accesses interfere. An explicit flow edge can
never waive a correctness dependency.

The runtime `DataFlowGraph` planner groups zero-indegree tasks into deterministic
execution stages, so independent tasks can be scheduled concurrently while
interfering tasks remain source/dependency ordered.

Whole-graph optimization may later use the same contract for:

- kernel fusion;
- temporary-buffer elimination;
- transfer batching;
- device residency;
- double buffering;
- NUMA placement;
- CPU/GPU scheduling.

## Static aspects

Oreslang aspects are static/AOT metadata, not runtime monkey-patching:

```ores
fnc trace(): void {
  return;
}

define aspect Telemetry as
  on task before trace;
  on gpu_launch after trace;
  on await suspend before trace;
  on await resume after trace;
end
```

Supported join-point families are:

- `fnc`;
- `task`;
- `actor_message`;
- `actor_spawn`;
- `await suspend` / `await resume`;
- `io`;
- `region_transfer`;
- `gpu_launch`;
- `error`;
- `rpc`;
- `database`.

The v0 advice kinds are `before` and `after`.

Aspect handlers must resolve statically to synchronous, zero-argument,
`void`, non-actor, non-generic `fnc` declarations. Runtime method
replacement/vtable patching is not part of the language.

`around` syntax remains reserved but is rejected by the semantic checker until
Oreslang defines a statically typed `proceed` / continuation ABI. This keeps
all scheduler, actor, I/O, and heterogeneous-compute effects visible to the
compiler.

## OOP + DOP composition

The intended application architecture is:

```ores
define class ParticleSystem as
  let Region<Particle> particles;

  pub update(f32 dt): void {
    // host/domain method arranges a compute launch through the runtime
    return;
  }
end
```

The object supplies identity, encapsulation, API shape, and lifecycle. The
region supplies dense analyzable data. Compute functions transform the region.
Flows expose cross-task dependencies. Static aspects apply cross-cutting policy
such as tracing/auditing without contaminating the data kernel.

This avoids the false choice between “everything is an object” and “no objects
are allowed”. Oreslang can use OOP where identity matters and DOP where
throughput/locality matters.

## Backend boundary

The current Java implementation establishes:

- parser/AST contracts;
- semantic validation;
- normalized compute metadata;
- logical region/slice/view planning types;
- conservative dependency analysis;
- CPU/GPU variant selection policy;
- deterministic dataflow staging;
- optimizer retention of compute/flow/aspect metadata.

It intentionally does **not** pretend that a production GPU executor exists.
A concrete SIMD/GPU backend must consume the checked contracts and preserve the
same observable Oreslang semantics in JIT, AOT, and hybrid execution profiles.
