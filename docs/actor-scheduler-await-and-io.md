# Actor scheduler, await, timers, and non-blocking I/O

This document defines the target Oreslang M:N actor scheduler.

The design intentionally follows proven runtime patterns rather than treating
every actor mailbox as an OS-level selectable channel.

## Scheduler domains

The process has three isolated scheduling domains:

1. shared actors;
2. private / isoactors;
3. untrusted actors.

Work never migrates across these domains. Each domain owns an elastic carrier
set (normally 5..20 threads), runnable queues, an I/O driver, timer state, and
wake/park coordination.

An actor may migrate between carriers inside its domain, but it may own at most
one execution lease at a time.

## Do not dynamically select every actor mailbox

There is no global `select(actor1.mailbox, actor2.mailbox, ...)`.

That design scales poorly because actor creation/removal would continually
rebuild or mutate one enormous wait set.

Instead, mailboxes and completion sources make an actor *runnable*.

Each actor cell has scheduler state conceptually equivalent to:

```
IDLE
QUEUED
RUNNING
RUNNING_NOTIFIED
SUSPENDED
STOPPED
```

A producer enqueues an event and performs a CAS wake transition. Only the first
wake that changes IDLE -> QUEUED places the actor on a run queue. Additional
messages do not enqueue duplicate actor identities.

If work arrives while RUNNING, the actor is marked notified. When its quantum
ends, it requeues itself exactly once.

This is the synchronization point that prevents two carriers from executing one
actor. A mutex per mailbox is not required for scheduler ownership; an atomic
lease/state transition is cheaper and easier to reason about.

## Run queues and work stealing

The final scheduler backend should use the established local-queue + global
injector + stealing pattern:

- each carrier has a local deque;
- external wakeups (I/O, timers, foreign threads) enter through a domain-global
  injector queue;
- a carrier prefers its local queue;
- periodically it checks the global queue for fairness;
- when idle it steals a batch from a sibling carrier;
- only a bounded fraction of carriers search/steal concurrently;
- when no work exists, a carrier parks instead of spinning.

Actors that remain runnable are requeued at the tail after a bounded quantum.
This gives BEAM-like reduction fairness while preserving cache locality.

The current JVM bootstrap dispatcher may use JDK executor machinery while this
contract is stabilized; the scheduler API must not expose executor/thread
identity to Oreslang code.

## Actor quantum

A lease runs a bounded quantum, limited by both operation/reduction count and
wall-clock time.

Within a quantum the actor prioritizes:

1. control/system events;
2. a continuation that woke the actor from await;
3. bounded next-tick callbacks;
4. ordinary FIFO mailbox messages.

An actor that is still runnable is requeued rather than recursively executed.

## Await is actor parking, never thread blocking

A pending `await future` is lowered to a stackless continuation.

Before suspension the compiler records:

- the resume program counter;
- live locals;
- temporary expression values needed after the await;
- exception/recover state;
- the actor identity and suspension generation.

Then the runtime registers a completion waker and unwinds back to the scheduler.

The carrier is immediately free to run another actor.

While the actor is suspended:

- its mailbox may continue accepting messages;
- ordinary mailbox messages do not execute;
- actor state therefore cannot be re-entered underneath the suspended method.

When the future completes, the completion thread only enqueues a resume event
and wakes the actor. Guest code never runs on the I/O completion thread.

When a carrier later acquires the actor lease, the resume continuation runs
before later mailbox messages and continues from the instruction after await.

Already-complete futures may take the synchronous fast path without yielding.

## Fire-and-forget asynchronous I/O

Starting async I/O does not suspend the actor by itself.

The actor may continue executing until it explicitly awaits that future or
returns from the turn.

This gives both patterns:

```
const a = http.get(url_a);
const b = http.get(url_b);

// local work may continue here

const [ra, rb] = await Futures.all([a, b]);
```

Only the await parks the actor.

## I/O driver

The scheduler must use readiness/completion-based OS I/O rather than blocking
carrier threads.

Backend mapping:

- Linux: epoll initially, io_uring where it provides a measurable benefit;
- macOS/BSD: kqueue;
- Windows: IOCP.

One logical I/O driver belongs to each scheduler domain. An idle carrier may
temporarily own the driver token and poll it; the design does not require a
permanent extra guest-execution thread.

I/O readiness produces wake events for actors/continuations. It never calls
guest code directly.

## Timers

Actor timers are futures and therefore use the same await/wake path.

For high timer cardinality the native scheduler should use a shared hierarchical
or hashed timing wheel per domain rather than one OS timer/thread per actor.
The wheel is advanced by the domain driver and expired timers enqueue wake
events.

The JVM bootstrap currently may use one control-plane timer driver while the
native timing wheel is implemented. That driver is forbidden from executing
guest callbacks.

## next_tick

`next_tick { ... }` (or the equivalent compiler intrinsic) means:

- never execute recursively in the current call stack;
- schedule actor-local continuation work for the next scheduler turn;
- requeue the actor at the tail, allowing peer actors to run first;
- cap next-tick callbacks per quantum so recursive next-tick scheduling cannot
  starve mailbox, timer, or I/O work.

A next-tick callback scheduled by another next-tick callback is a later
generation and cannot run inline.

## Cancellation and stale wakeups

Every suspension/timer registration carries an actor-local generation token.
Stop/restart/termination invalidates prior generations.

Late I/O/timer completions with a stale generation are ignored, preventing
resurrection of a dead actor or resumption of an obsolete continuation.

## Untrusted actors

All of the above scheduling rules apply to untrusted actors plus their stronger
sandbox constraints:

- dedicated scheduling domain;
- one-message/restricted continuation quantum;
- fuel accounting at compiler safepoints;
- hard lifetime;
- bounded memory and mailbox return data;
- bounded stateless outbound HTTP;
- no raw TCP authority and no child actor spawning.

Awaiting I/O must never be a mechanism for escaping those quotas.

## Fairness

The scheduler should account for reductions/operations, not just message count.
Compiler-injected checkpoints charge loops, calls, allocations, pattern
matching, and other bounded units.

A quantum ends when either the reduction budget or wall-clock budget is
exhausted. This makes actors with one CPU-heavy message coexist fairly with
actors processing many small messages.
