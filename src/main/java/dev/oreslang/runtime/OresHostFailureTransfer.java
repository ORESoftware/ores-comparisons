package dev.oreslang.runtime;

import java.util.Objects;

/**
 * One-shot transfer of an original root-task failure across the Truffle host
 * boundary.
 *
 * <p>PolyglotException intentionally normalizes guest exceptions and does not
 * preserve arbitrary Java suppressed/cause metadata. Oreslang's async causal
 * trace is attached to the original failure before that boundary, so a trusted
 * host invocation may opt into a thread-confined capture scope immediately
 * around the Polyglot call.</p>
 *
 * <p>Direct embedders do not implicitly participate. Outside an explicit
 * capture scope {@link #publish(Throwable)} is a no-op, preventing stale
 * failures from being retained on long-lived host threads. Nested captures
 * restore the prior state when they close.</p>
 */
public final class OresHostFailureTransfer {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private OresHostFailureTransfer() { }

    public static Capture capture() {
        State previous = CURRENT.get();
        State current = new State();
        CURRENT.set(current);
        return () -> {
            current.failure = null;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static void publish(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        State state = CURRENT.get();
        if (state != null) state.failure = failure;
    }

    public static Throwable consume() {
        State state = CURRENT.get();
        if (state == null) return null;
        Throwable failure = state.failure;
        state.failure = null;
        return failure;
    }

    public static void clear() {
        State state = CURRENT.get();
        if (state != null) state.failure = null;
    }

    public interface Capture extends AutoCloseable {
        @Override
        void close();
    }

    private static final class State {
        private Throwable failure;
    }
}
