# Compiler-lowered async state machines

Oreslang's current source evaluator uses the `AsyncPlan` family
(`AsyncThunk`, `AsyncAwait`, `AsyncTailTransfer`, and composition helpers such
as `asyncFlatMap`). That path is the semantic reference implementation: it is
stackless, preserves the hard scheduler boundary of `await`, and already
implements logical async traces and proper tail-await transfer.

It is not the production optimization target.

## Production lowering

A production async callable is lowered to a resumable frame with an integer
program counter and only the values whose live ranges cross suspension.

Conceptually:

```java
final class CompiledAsyncFoo implements OresScheduler.Task<Result> {
    int pc;

    // Only suspension-live values.
    User user;
    Request request;

    @Override
    public OresScheduler.Step<Result> resume(OresScheduler.Resume resume) {
        for (;;) {
            switch (pc) {
                case 0 -> {
                    user = lookupUser();

                    OresFuture<Response> future = fetch(user);
                    pc = 1;
                    return OresScheduler.await(future);
                }

                case 1 -> {
                    Response response = resumeValue(resume);

                    // user is dead after this point. Generated code clears the
                    // reference before the next scheduler boundary.
                    user = null;

                    pc = 2;
                    return OresScheduler.done(transform(response));
                }

                default -> throw invalidState();
            }
        }
    }
}
```

The compiler must not materialize every source local as a frame field. A local
that is created and dies between suspension points remains an ordinary
Truffle/JVM local and is eligible for normal partial evaluation, scalar
replacement, register allocation, and stack placement.

## Compiler pipeline

The lowering pipeline is:

1. parse, type-check, resolve names, and establish ownership/effect rules;
2. closure-convert nested lambdas and give lexical bindings stable compiler IDs;
3. normalize the callable into control-flow/basic blocks;
4. make every `await` an explicit suspension terminator;
5. run backwards liveness over the normalized control-flow graph;
6. allocate persistent frame slots only for lexical values live across an
   `await`;
7. allocate a small reusable temporary spill bank for already-computed
   expression values that must survive suspension;
8. assign dense resume PCs;
9. emit a direct `switch (pc)` resume loop;
10. before yielding, clear reference spill fields that are dead for the next
    suspension;
11. preserve tail-await as frame replacement rather than rebuilding a caller
    continuation.

`AsyncSuspensionAnalysis` implements the source-AST form of steps 5-8 today.
`AsyncStateMachinePlan` is the compiler-owned seam that exposes the planned
frame and resume states to later code generation.

The source-AST pass resolves lexical bindings to compiler-owned stable IDs
before liveness. Shadowed names are therefore distinct even when an inner and
outer binding are simultaneously live at the same suspension point. This is a
correctness property, not just an optimization: two live bindings named `x`
must never be collapsed into one frame field. A later normalized CFG may carry
the same IDs forward directly.

## Suspension-aware liveness

For each await, the compiler records:

- lexical spill slots whose values are live after resumption;
- reference spill slots that are dead and can be cleared before suspension;
- how many already-computed expression temporaries must cross the await.

Example:

```ores
async fnc example(): Result {
  // 28 unrelated locals that die here ...

  val user = lookup_user();
  val request = make_request(user);

  val response = await fetch(request);
  return transform(user, response);
}
```

Only `user` and any other value used after the await need persistent lexical
slots. The unrelated locals remain ordinary temporaries.

A second important case is expression evaluation:

```ores
return combine(1, await fetch(), 3);
```

The first argument has already been evaluated when `fetch()` suspends, so one
temporary spill is necessary even if no named lexical local is live across the
await.

## Reference clearing and GC

Persistent async frames can be long-lived, especially when owned by actors.
Generated code must not retain dead object graphs merely because a field once
held a reference.

Reference fields should therefore be cleared at the earliest proven point.
The liveness result supplies `clearReferenceSlots` for every suspension point.
A generated state may also clear a reference immediately after its last use
within the resumed state.

Primitive spill fields do not require GC clearing.

## Truffle boundary strategy

Do **not** simply remove `@TruffleBoundary` from the existing monolithic source
evaluator. That would expose a dynamic AST interpreter, hash-map environments,
and functional `AsyncPlan` machinery to partial evaluation and can easily make
compilation larger or worse.

Instead:

- keep the legacy source evaluator behind its current boundary as a correctness
  and fallback path;
- make the compiler-lowered async resume path a normal Truffle-compilable path;
- keep slow diagnostics, reflection/interop fallback, and uncommon error
  handling behind narrow boundaries;
- ensure the generated hot resume loop itself is not hidden behind a
  `@TruffleBoundary`.

The goal is for Graal to see a small state machine with predictable fields and
control flow, not the generic source evaluator.

## Scheduler ABI: measure before changing it

`OresScheduler.Step`, `Await`, `Done`, and `Resume` are currently records.
They are conceptually allocations, although Graal can scalar-replace some of
them in JIT mode.

Do not make the scheduler ABI more complicated until allocation profiles exist
for both JIT and native/AOT modes.

If these objects survive materially, the next ABI can move the action/result
onto the frame or runner:

```text
frame.next_action = AWAIT
frame.awaited_future = future
frame.pc = 7
scheduler.run(frame)
```

That would allow a pending await to approach:

```text
write pc
write awaited future
register/enqueue waiter
return
```

and the already-completed path to avoid continuation/waiter allocation where
the Future representation permits it. Those changes come after the compiler
frame lowering is measured, not before.

## Correctness invariants

The compiled path must preserve the existing async contract:

- `await` always returns through the owning scheduler;
- even an already-completed Future never resumes the continuation inline on the
  current guest stack;
- the current turn fully unwinds before the continuation can execute;
- producer/I/O/JNI/timer threads may make a task runnable but never execute
  guest continuation code;
- actor single-execution leases remain intact across suspension/resumption;
- cancellation and failures are delivered exactly once;
- `defer`, `catch`, and `finally` edges remain explicit and cannot be
  skipped by tail transfer;
- logical async trace sites survive lowering;
- proper tail-await replaces the current logical frame only when the compiler
  has proven there is no remaining cleanup/result work.

The `AsyncPlan` evaluator remains useful as a differential oracle: the same
source program should produce the same value/failure, side effects, scheduler
boundaries, and logical trace under both the reference evaluator and compiled
state-machine lowering.
