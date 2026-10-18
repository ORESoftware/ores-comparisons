package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Minimal runtime contract for compiler-generated async state-machine frames.
 *
 * <p>A generated final subclass owns exactly its suspension-live fields plus
 * this inherited program counter. Source locals that do not cross an await
 * remain ordinary JVM/Truffle temporaries in {@link #resume} and never become
 * frame fields.</p>
 *
 * <p>This class intentionally keeps the existing {@link OresScheduler.Step}
 * and {@link OresScheduler.Resume} ABI. Whether those records survive escape
 * analysis in JIT/AOT mode is a profiling question and is separate from
 * compiler-side frame lowering.</p>
 */
public abstract class OresCompiledAsyncFrame<T>
        implements OresScheduler.Task<T> {

    /** Dense compiler-assigned resume program counter. Entry is always 0. */
    protected int pc;

    public final int programCounter() {
        return pc;
    }

    /**
     * Validate the first dispatch of a generated frame.
     */
    protected final void requireInitial(OresScheduler.Resume resume) {
        Objects.requireNonNull(resume, "resume");
        if (!resume.initial()) {
            throw new IllegalStateException(
                    "compiled async frame pc=" + pc
                            + " expected initial scheduler dispatch");
        }
    }

    /**
     * Validate a post-await dispatch.
     */
    protected final void requireResumed(OresScheduler.Resume resume) {
        Objects.requireNonNull(resume, "resume");
        if (resume.initial()) {
            throw new IllegalStateException(
                    "compiled async frame pc=" + pc
                            + " expected resumed scheduler dispatch");
        }
    }

    /**
     * Extract a successful await result. Generated catch/finally lowering may
     * instead inspect {@link OresScheduler.Resume#failure()} directly when the
     * failure is an explicit CFG edge.
     */
    @SuppressWarnings("unchecked")
    protected final <V> V resumedValue(OresScheduler.Resume resume) {
        requireResumed(resume);
        if (resume.failure() != null) {
            throw propagateResumeFailure(resume.failure());
        }
        return (V) resume.value();
    }

    /**
     * Commit the next PC before exposing the awaited Future to the scheduler.
     * This ordering is part of the generated-frame ABI.
     */
    protected final OresScheduler.Step<T> suspend(
            int resumePc,
            OresFuture<?> future) {
        if (resumePc < 0) {
            throw new IllegalArgumentException(
                    "resume pc must be non-negative");
        }
        pc = resumePc;
        return OresScheduler.await(
                Objects.requireNonNull(future, "future"));
    }

    /**
     * Proper async tail transfer remains a hard scheduler boundary. Generated
     * lowering updates its logical target state before returning this step.
     */
    protected final OresScheduler.Step<T> tailTransfer(int resumePc) {
        if (resumePc < 0) {
            throw new IllegalArgumentException(
                    "resume pc must be non-negative");
        }
        pc = resumePc;
        return OresScheduler.tailAwait();
    }

    protected final OresScheduler.Step<T> done(T value) {
        return OresScheduler.done(value);
    }

    private static RuntimeException propagateResumeFailure(
            Throwable failure) {
        Throwable unwrapped = OresFuture.unwrap(
                Objects.requireNonNull(failure, "failure"));
        if (unwrapped instanceof RuntimeException runtime) return runtime;
        if (unwrapped instanceof Error error) throw error;
        return new RuntimeException(unwrapped);
    }
}
