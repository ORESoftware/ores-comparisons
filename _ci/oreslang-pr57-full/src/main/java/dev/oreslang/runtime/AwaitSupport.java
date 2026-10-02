package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
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


    /**
     * Convert any supported backend awaitable into a non-blocking completion
     * stage suitable for compiler-lowered Oreslang continuations.
     *
     * <p>Host primitives whose only waiting API is blocking are observed from a
     * Java virtual thread. The returned CompletionStage is a minimal/read-only
     * view so host code cannot forge completion of the runtime-owned adapter.</p>
     */
    public static CompletionStage<?> toCompletionStage(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("await requires an awaitable value, got null");
        }

        if (value instanceof CompletionStage<?> stage) {
            CompletableFuture<Object> adapted = new CompletableFuture<>();
            stage.whenComplete((result, failure) -> {
                if (failure != null) adapted.completeExceptionally(unwrapCompletion(failure));
                else adapted.complete(result);
            });
            return adapted.minimalCompletionStage();
        }

        if (value instanceof Future<?> future) {
            CompletableFuture<Object> adapted = new CompletableFuture<>();
            if (future.isDone()) {
                completeFromFuture(future, adapted);
            } else {
                Thread.startVirtualThread(() -> completeFromFuture(future, adapted));
            }
            return adapted.minimalCompletionStage();
        }

        if (value instanceof Thread thread) {
            if (thread == Thread.currentThread()) {
                throw new IllegalStateException("a thread cannot await its own completion");
            }
            if (thread.getState() == Thread.State.NEW) {
                throw new IllegalArgumentException("cannot await a thread that has not been started");
            }

            CompletableFuture<Object> adapted = new CompletableFuture<>();
            if (!thread.isAlive()) {
                adapted.complete(null);
            } else {
                Thread.startVirtualThread(() -> {
                    try {
                        thread.join();
                        adapted.complete(null);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        CancellationException cancelled = new CancellationException("await adapter interrupted");
                        cancelled.initCause(interrupted);
                        adapted.completeExceptionally(cancelled);
                    }
                });
            }
            return adapted.minimalCompletionStage();
        }

        if (value instanceof Awaitable<?> awaitable) {
            CompletableFuture<Object> adapted = new CompletableFuture<>();
            Runnable wait = () -> {
                try {
                    adapted.complete(awaitable.await());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    CancellationException cancelled = new CancellationException("await adapter interrupted");
                    cancelled.initCause(interrupted);
                    adapted.completeExceptionally(cancelled);
                } catch (Exception failure) {
                    adapted.completeExceptionally(failure);
                }
            };
            if (awaitable.isDone()) wait.run();
            else Thread.startVirtualThread(wait);
            return adapted.minimalCompletionStage();
        }

        throw new IllegalArgumentException(
                "await requires an Oreslang Future or supported backend awaitable, got "
                        + value.getClass().getName());
    }

    private static void completeFromFuture(Future<?> future, CompletableFuture<Object> adapted) {
        try {
            adapted.complete(future.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("await adapter interrupted");
            cancelled.initCause(interrupted);
            adapted.completeExceptionally(cancelled);
        } catch (ExecutionException failure) {
            adapted.completeExceptionally(failure.getCause() == null ? failure : failure.getCause());
        } catch (CancellationException cancelled) {
            adapted.completeExceptionally(cancelled);
        }
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        if ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
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
