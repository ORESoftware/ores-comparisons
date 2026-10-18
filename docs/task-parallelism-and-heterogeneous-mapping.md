# Task parallelism and heterogeneous CPU/GPU mapping

Oreslang should borrow the **logical/physical separation** of Regent and Legion,
without copying Regent syntax or weakening the Oreslang actor/isolation model.

Primary design reference:

- Elliott Slaughter, *Regent: A High-Productivity Programming Language for
  Implicit Parallelism with Logical Regions*, Stanford University, 2017:
  https://theory.stanford.edu/~aiken/publications/theses/slaughter.pdf

Regent's useful lessons for Oreslang are:

1. tasks describe their logical data use (read, write, reduction);
2. independence is a correctness question derived from those effects and aliasing;
3. physical placement is a separate performance question;
4. a runtime mapper may choose architecture-specific task variants;
5. data movement/physical instances should normally be managed by the
   compiler/runtime rather than exposed as application bookkeeping; and
6. the observable result must remain compatible with the language's sequential
   semantics unless the programmer explicitly opts into weaker coherence.

This document is an Oreslang contract. It is not a claim that the current VM
already contains a GPU execution backend.

## Actors and tasks are different abstractions

Actors remain Oreslang's stateful isolation and supervision abstraction:

- one active execution lease per actor;
- actor-owned mutable state;
- mailbox ingress;
- actor scheduler domain;
- supervision, lifetime, capability, and memory budgets.

A task is a finite unit of computation over explicitly supplied data. A task is
**not** an actor and does not gain ambient access to the launching actor's state.

An actor may eventually launch tasks, but concurrent task execution may operate
only on data represented by task-region arguments whose effects are declared and
validated. Actor-owned fields remain under the actor lease unless data is
explicitly detached/copied/moved into an approved task region.

This prevents "GPU task" from becoming a back door around actor isolation.

## Logical regions and privileges

The compiler/runtime task IR should represent logical region requirements. At a
minimum each requirement contains:

- logical region/slice identity;
- fields or a whole-region footprint;
- privilege: **read**, **write**, or **reduce**;
- reduction operator when applicable;
- an optional runtime/compiler-certified disjoint-partition proof.

Ordinary guest code must not be able to forge region identity or disjointness
proofs.

Two tasks may be reordered or overlapped only when the runtime/compiler proves
that their requirements do not interfere. The conservative baseline is:

- read + read on an aliasing slice: independent;
- read + write: ordered;
- write + write: ordered;
- any reduction on an aliasing slice: ordered until that reduction operator is
  separately registered/proved safe for parallel combination;
- accesses to different root regions: independent;
- accesses to sibling slices are independent only when disjointness was
  certified by the compiler/runtime.

It is always legal for analysis to be too conservative. It is never legal to
guess independence.

## Sequential semantics

Task calls are logically issued in source/program order. The runtime may execute
independent work out of order or concurrently, but results must be
indistinguishable from a valid sequential execution under the default
coherence model.

There is no implicit requirement for a global barrier after each task or each
task-producing loop. Synchronization is introduced only by real data
dependencies, explicit structured-concurrency boundaries, an `await`, or an
explicit barrier/coherence construct.

This is how Oreslang can expose substantial parallelism without making ordinary
source code reason like CUDA kernels or MPI ranks.

## Correctness before placement

Dependency analysis and processor placement are separate phases.

The **dependency/effect layer** answers:

> May these tasks overlap without violating Oreslang semantics?

Only after the answer is yes may the **mapper** answer:

> Where should this task run?

The mapper must not be able to waive ownership, capability, actor, aliasing, or
effect checks.

## CPU/GPU variants

One logical task may eventually have multiple implementation variants, for
example:

- scalar/JIT CPU;
- SIMD CPU;
- multicore CPU;
- GPU compute;
- architecture-specific AOT variants.

Variants share one logical signature/effect contract. A GPU variant is not a
different semantic task.

The compiler/runtime validates variant compatibility before registration:

- same logical arguments/results;
- same or narrower declared effects;
- compatible failure/cancellation behavior;
- compatible numeric/overflow semantics, or an explicit semantic mode that says
  otherwise;
- required memory-layout constraints;
- backend capability requirements.

A variant that cannot preserve the task contract is not eligible.

## AUTO placement

`AUTO` should be the normal placement mode. The default mapper may consider:

- whether a compatible CPU/GPU variant exists;
- estimated work size;
- data already resident near/on a GPU;
- bytes that would need transfer;
- output/write-back cost;
- current CPU/GPU queue pressure;
- NUMA/locality information;
- memory capacity;
- launch overhead;
- task deadline/priority;
- execution profile (JIT/AOT/hybrid);
- actor trust/isolation domain.

Small jobs should normally remain on CPU when GPU launch/transfer overhead would
dominate. Already-resident large jobs may favor GPU. These are performance
heuristics only; they must never change correctness.

Explicit `CPU` or `GPU` placement is a constraint, not permission to violate
the task contract. Explicit GPU placement fails closed when no eligible GPU
backend/variant exists.

## Data placement and transfers

Logical region identity is not a physical address.

A backend may hold zero, one, or several physical instances of a logical region,
including read-only replicas. The runtime owns coherence and transfer planning.

Host-to-device/device-to-host copies should be represented as asynchronous
dependency-graph operations. They should not normally block an Ores scheduler
carrier and must integrate with `Future<T>`/`Awaitable<T>`.

A mapper should prefer existing valid physical instances and avoid needless
round trips. Writable replicas require explicit coherence handling before their
results become visible elsewhere.

## Loop/index launches

A future parallel-task loop can be optimized when the compiler can prove that
the iteration region requirements are non-interfering. For example, iteration
`i` accessing a certified-disjoint partition `p[i]` is a natural candidate
for an index/batched launch.

The ordinary loop syntax introduced in Oreslang is **not automatically a
parallel loop**. A normal `for` or `while` retains ordinary sequential loop
semantics unless its body launches task operations whose dependency graph permits
overlap, or a future explicit parallel-loop construct says otherwise.

This separation avoids making every collection iteration a hidden concurrency
primitive.

## Scheduler and await integration

Launching an asynchronous task produces an Ores-owned awaitable/future handle.

- submission must not block an actor/root scheduler carrier;
- completion never resumes Oreslang inline;
- continuations return through the waiting task's owning scheduler;
- task dependencies may delay physical execution without occupying a carrier;
- cancellation propagates through runtime-owned task handles;
- a parent scope does not silently forget live child tasks.

The task mapper is not a fifth actor scheduler domain. CPU task execution may
use appropriate runtime worker resources; GPU execution is a device backend.
Actor scheduler-domain ownership still controls guest continuation execution.

## Untrusted actors

Untrusted actors do **not** receive raw CUDA/OpenCL/Vulkan/Metal/device handles,
native pointers, mapper authority, or physical-instance objects.

GPU execution for untrusted actors remains disabled until a backend can enforce
all existing untrusted guarantees, including:

- lifetime/deadline termination;
- memory/resource quotas;
- capability restrictions;
- bounded result/output transfer;
- reliable resource reclamation after completion/cancellation;
- no access to another actor/runtime's device allocations.

A faster device is never a justification for weakening the sandbox.

## AOT/JIT requirements

The task/effect contract is part of the semantic/AOT floor.

AOT builds need a closed registry of eligible task variants and backend
requirements. JIT may specialize or optimize additional variants, but it cannot
change the logical effect contract or depend on semantics unavailable to AOT.

Hot-loaded actor/task code must still match its declared ABI/effect contract
before a variant can become active.

## Current implementation boundary

`HeterogeneousTaskMapper` is currently a **pure planning contract**:

- conservative non-interference checking for logical region requirements;
- whole-slice and field-scoped read/write/reduce footprints;
- compiler/runtime-certified disjoint partition representation;
- CPU/GPU variant metadata;
- transfer-aware AUTO placement;
- fail-closed explicit GPU placement.

It does not execute GPU code, expose host/device pointers, or add a source-level
`task` keyword.

Keeping the source spelling undecided is intentional. We should reserve syntax
only when the task ABI/effect system is mature enough to implement it rather
than consume a keyword for a speculative surface design.
