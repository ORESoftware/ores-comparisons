# Actor continuations, timers, and deferred turns

This document defines the resumable scheduling substrate layered on top of the
three-domain actor event loop.

## Core invariant

An actor owns at most one active carrier execution lease. Suspending an actor
does **not** suspend or pin that carrier. The compiler lowers a resumable point
into a heap-safe continuation, registers the continuation with the awaited
operation, and returns the carrier to the appropriate actor pool.

Completion sources never execute guest actor code directly.

```text
I/O / Future / timer
        |
        v
runtime completion callback
        |
        | enqueue only
        v
actor continuation queue
        |
        v
SHARED / PRIVATE / UNTRUSTED scheduler
        |
        v
acquire actor execution lease
        |
        v
resume guest continuation
```

A continuation may resume on a different carrier thread from the one that
suspended it. Actor identity and memory ownership therefore never depend on
carrier-thread identity.

## `await`

Compiler lowering for actor-side `await` is conceptually:

```text
before await:
  evaluate awaited Future
  capture program counter + live locals in ActorContinuation
  ActorContext.suspendOn(future, continuation)

runtime:
  register completion callback
  mark the logical turn suspended
  unwind the current interpreter/native frame
  release actor execution lease

completion:
  enqueue continuation
  mark actor runnable

resume:
  scheduler reacquires actor execution lease
  restore continuation state
  deliver result or failure at the await site
```

`await` is always a scheduling boundary. Even an already-completed future is
resumed on a later actor scheduling turn rather than continuing inline.

While a mailbox turn is suspended, later mailbox messages for that actor do not
run. The actor therefore retains sequential reasoning across `await`.
The frozen/admitted mailbox envelope remains rooted and memory-charged until the
logical turn finally completes, because the continuation may still reference
values from that message.

Waiting time itself does not consume a carrier or a message watchdog window.
When the continuation resumes, the runtime arms a fresh CPU-execution watchdog
for the resumed segment. Untrusted fuel remains attached to the logical event;
an `await` cannot replenish fuel simply by suspending.

## `next_tick`

`next_tick` is an actor-local deferred continuation:

- it never executes inline;
- it runs under the same actor execution lease and actor domain;
- at most one deferred callback is consumed per scheduler quantum;
- a newly queued `next_tick` ends the current mailbox batch so it can run
  before the actor consumes another mailbox message;
- its queue is bounded.

This prevents recursive microtask chains from monopolizing one carrier.

## Timers

Actor timers use a hashed timing wheel. A timer-driver thread advances wheel
slots and performs only a small runtime callback that enqueues the actor
continuation.

The timer driver never enters guest code, never acquires guest actor state, and
never becomes an actor carrier.

Timer handles support cancellation. Actor teardown cancels all outstanding
timers and drops queued continuation events.

## Completion queue bounds

Timer, I/O, resumed-await, and deferred events are control-plane work and must
not become an unbounded side mailbox. Each actor therefore has a bounded
continuation-event admission counter derived from its mailbox policy and capped
at a process-safe maximum.

Exceeding that bound is fail-closed rather than silently allocating an
unbounded queue.

## Scheduler-forced soft preemption

Actor continuation machinery is also the lowering target for BEAM-style soft
realtime preemption. An actor does not need to reach `await` before another
actor can receive the carrier.

Each carrier slice has two independent fairness triggers:

- a reduction budget (currently 2,000 compiler/runtime checkpoints);
- the actor domain's wall-clock batch quantum.

The scheduler may also explicitly request preemption for a running actor. Any
of those conditions sets a one-shot preemption request. The request is serviced
at the next **preemption-safe** checkpoint emitted by the compiler:

```text
actor owns carrier + execution lease
        |
        | reductions/time/manual request
        v
preemption requested
        |
        | next resumable safe point
        v
capture heap-safe continuation
        |
        v
unwind guest stack
release actor execution lease
requeue actor at scheduler tail
        |
        v
carrier immediately becomes available to peer work
```

This is scheduler-forced **cooperative** preemption, not Java thread suspension.
The runtime never uses `Thread.suspend`, `Thread.stop`, or asynchronous stack
tearing. A request can be raised at any time, but handoff latency is bounded by
the next compiler safe point. Code that enters an opaque non-resumable host/FFI
stack cannot be safely detached mid-call; watchdog/sandbox policy remains the
containment mechanism for such code.

A preempted mailbox event remains the same logical actor turn. Its admitted
message stays rooted/accounted, later mailbox messages cannot overtake it, and
untrusted fuel is not replenished. Resumption may use a different carrier but
still requires the same actor execution lease.

Carrier-thread-affine capabilities form temporary **pinned regions**. For
example, an `OresRwLock` guard cannot migrate between JVM threads. A pending
preemption remains pending while such a guard is live and is serviced at the
next safe checkpoint after the guard closes. The compiler must likewise avoid
emitting migration-capable checkpoints across foreign/native frames that cannot
be safely resumed elsewhere.

`await` remains stronger and unconditional: it always ends the current actor
scheduler turn, including when the Future is already complete. Reduction or
wall-clock budget does not change that rule.

## `yield` is not actor scheduling

The language keyword `yield` is reserved for generators/iterators.

A generator `yield value` suspends a generator frame and hands a value to the
generator's consumer. It is **not** a request to relinquish an actor carrier,
does not alter mailbox ordering, and is not required for scheduler fairness.

Generator lowering can reuse the compiler's general resumable-frame machinery,
but generator continuations and actor-await continuations have different owners
and wakeup rules. Keeping them separate prevents source-level iterator behavior
from becoming part of the actor liveness contract.
