package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * JVM-backend adapter for the Oreslang await contract.
 *
 * Source code sees Future<T>. Backend integrations may use different host
 * completion primitives, but they all converge here instead of leaking Java
 * classes into the Oreslang ABI.
 */
public final class AwaitSupport {
    private AwaitSupport() { }

    /**
     * Internal extension point for runtime/native adapters that are neither a
     * CompletionStage nor a java.util.concurrent.Future.
     */
    public interface Awaitable<T> {
        boolean isDone();
        T await() throws Exception;
    }

    public static Object await(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("await requires an awaitable value, got null");
        }

        if (value instanceof CompletionStage<?> stage) {
            var future = stage.toCompletableFuture();
            rejectPendingActorCarrier(future.isDone(), "CompletionStage");
            try {
                return future.join();
            } catch (CompletionException failure) {
                throw propagate(failure.getCause());
            }
        }

        if (value instanceof Future<?> future) {
            rejectPendingActorCarrier(future.isDone(), "Future");
            try {
                return future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("await interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            } catch (ExecutionException failure) {
                throw propagate(failure.getCause());
            }
        }

        if (value instanceof Thread thread) {
            if (thread == Thread.currentThread()) {
                throw new IllegalStateException("a thread cannot await its own completion");
            }
            if (thread.getState() == Thread.State.NEW) {
                throw new IllegalArgumentException("cannot await a thread that has not been started");
            }
            rejectPendingActorCarrier(!thread.isAlive(), "Thread");
            try {
                thread.join();
                return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("await interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        if (value instanceof Awaitable<?> awaitable) {
            rejectPendingActorCarrier(awaitable.isDone(), "Awaitable");
            try {
                return awaitable.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("await interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            } catch (Exception failure) {
                throw propagate(failure);
            }
        }

        throw new IllegalArgumentException(
                "await requires an Oreslang Future or supported backend awaitable, got "
                        + value.getClass().getName());
    }

    private static void rejectPendingActorCarrier(boolean done, String kind) {
        if (!done && ActorRuntime.isActorCarrierThread()) {
            throw new IllegalStateException(
                    "pending " + kind + " await would block an actor dispatcher carrier; "
                            + "actor/async lowering must suspend the Oreslang continuation instead");
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("awaited task failed", failure);
    }
}
