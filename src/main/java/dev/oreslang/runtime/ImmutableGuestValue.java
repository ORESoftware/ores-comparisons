package dev.oreslang.runtime;

/**
 * Marker for deeply immutable runtime-owned guest values that may cross trusted
 * actor boundaries by reference without exposing mutable host state.
 *
 * This interface is sealed because ActorRuntime treats permitted implementations\n * as audited, deeply immutable scalar values and may transport them by reference.\n * New implementations must be added deliberately to the permits list after their\n * backing state and quota accounting are audited.\n *\n * Implementations must never expose a mutable backing object. logicalBytes()
 * is a conservative language-level quota footprint, not a JVM layout promise.
 */
public sealed interface ImmutableGuestValue extends OresMutex.SharedState
        permits dev.oreslang.runtime.math.OresComplex,
                dev.oreslang.runtime.math.OresMatrix,
                dev.oreslang.runtime.math.OresVector {
    long logicalBytes();
}
