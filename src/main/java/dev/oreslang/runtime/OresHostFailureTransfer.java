package dev.oreslang.runtime;

import java.util.Objects;

/**
 * One-shot transfer of an original root-task failure across the Truffle host
 * boundary.
 *
 * <p>PolyglotException intentionally normalizes guest exceptions and does not
 * preserve arbitrary Java suppressed/cause metadata. Oreslang's async causal
 * trace is attached to the original failure before that boundary, so the
 * trusted launcher records it on the calling host thread immediately before
 * Truffle wraps the exception and consumes it immediately after the call
 * returns exceptionally.</p>
 *
 * <p>This is not guest-visible state and never crosses threads. Every host
 * invocation clears the slot before and after execution to prevent stale
 * provenance from contaminating a later request.</p>
 */
public final class OresHostFailureTransfer {
    private static final ThreadLocal<Throwable> ROOT_FAILURE = new ThreadLocal<>();

    private OresHostFailureTransfer() { }

    public static void publish(Throwable failure) {
        ROOT_FAILURE.set(Objects.requireNonNull(failure, "failure"));
    }

    public static Throwable consume() {
        Throwable failure = ROOT_FAILURE.get();
        ROOT_FAILURE.remove();
        return failure;
    }

    public static void clear() {
        ROOT_FAILURE.remove();
    }
}
