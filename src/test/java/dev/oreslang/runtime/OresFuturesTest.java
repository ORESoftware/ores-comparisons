package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class OresFuturesTest {

    @Test
    void allPreservesInputOrderIndependentOfCompletionOrder() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();
        CompletableFuture<Integer> third = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second, third));
        third.complete(3);
        first.complete(1);
        assertFalse(all.isDone());

        second.complete(2);
        assertEquals(List.of(1, 2, 3), all.join());
    }

    @Test
    void allPropagatesFailure() {
        CompletableFuture<Integer> ok = new CompletableFuture<>();
        CompletableFuture<Integer> bad = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(ok, bad));
        ok.complete(1);
        bad.completeExceptionally(new IllegalStateException("boom"));

        CompletionException failure = assertThrows(CompletionException.class, all::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void cancellingAggregateCancelsChildren() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second));
        assertTrue(all.cancel(true));

        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
    }

    @Test
    void guestCannotForgeFutureCompletion() {
        OresFuture<Integer> future = new OresFuture<>();

        assertThrows(UnsupportedOperationException.class, () -> future.complete(99));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.completeExceptionally(new IllegalStateException("forged")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.completeOnTimeout(99, 1, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.orTimeout(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertFalse(future.isDone());
    }

    @Test
    void oresFutureDoesNotExposeCompletionStageCallbackSurface() {
        assertFalse(
                java.util.concurrent.CompletionStage.class.isAssignableFrom(
                        OresFuture.class));
        assertFalse(
                java.util.Arrays.stream(OresFuture.class.getMethods())
                        .anyMatch(method -> method.getName().equals("thenApply")
                                || method.getName().equals("thenAccept")
                                || method.getName().equals("thenRun")),
                "guest-visible OresFuture must not inherit producer-thread callback APIs");
    }

    @Test
    void runtimeWaiterIsDeliveredExactlyOnceWhenRegistrationRacesCompletion()
            throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            OresFuture<Integer> future = new OresFuture<>();
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger callbacks =
                    new java.util.concurrent.atomic.AtomicInteger();

            Thread registrar = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                future.whenCompleteRuntime((value, failure) -> {
                    assertNull(failure);
                    assertEquals(7, value);
                    callbacks.incrementAndGet();
                });
            });
            Thread completer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                future.completeFromRuntime(7);
            });

            start.countDown();
            registrar.join();
            completer.join();

            assertEquals(7, future.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, callbacks.get(),
                    "completion/registration race must not duplicate a waiter");
        }
    }

    @Test
    void raceCompletesWithFirstCompletion() {
        CompletableFuture<Integer> slow = new CompletableFuture<>();
        CompletableFuture<Integer> fast = new CompletableFuture<>();

        OresFuture<Integer> race = OresFutures.race(List.of(slow, fast));
        fast.complete(7);

        assertEquals(7, race.join());
    }
    @Test
    void cancelledCompletionStageNormalizesToCancelledOresFuture() {
        CompletableFuture<Integer> host = new CompletableFuture<>();
        OresFuture<Integer> ores = OresFuture.from(host);

        assertTrue(host.cancel(true));

        assertTrue(ores.isCancelled());
        assertThrows(CancellationException.class, ores::join);
    }

    @Test
    void allPreservesChildCancellationAsCancellation() {
        OresFuture<Integer> cancelled = new OresFuture<>();
        OresFuture<Integer> sibling = new OresFuture<>();
        OresFuture<List<Integer>> all = OresFutures.all(List.of(cancelled, sibling));

        assertTrue(cancelled.cancel(true));

        assertTrue(all.isCancelled());
        assertThrows(CancellationException.class, all::join);
        assertFalse(sibling.isCancelled(),
                "external cancellation of one child must not synthesize cancellation of siblings");
    }

    @Test
    void racePreservesWinningCancellationAsCancellation() {
        OresFuture<Integer> cancelled = new OresFuture<>();
        OresFuture<Integer> never = new OresFuture<>();
        OresFuture<Integer> race = OresFutures.race(List.of(cancelled, never));

        assertTrue(cancelled.cancel(true));

        assertTrue(race.isCancelled());
        assertThrows(CancellationException.class, race::join);
    }

    @Test
    void allSettledRecordsCancellationSeparatelyFromFailure() {
        OresFuture<Integer> cancelled = new OresFuture<>();
        OresFuture<Integer> failed = OresFuture.failed(new IllegalStateException("boom"));
        OresFuture<List<OresFutures.Settled<Integer>>> all =
                OresFutures.allSettled(List.of(cancelled, failed));

        assertTrue(cancelled.cancel(true));
        List<OresFutures.Settled<Integer>> settled = all.join();

        assertTrue(settled.get(0).cancelled());
        assertFalse(settled.get(0).ok());
        assertInstanceOf(CancellationException.class, settled.get(0).error());
        assertFalse(settled.get(1).cancelled());
        assertInstanceOf(IllegalStateException.class, settled.get(1).error());
    }

    @Test
    void runtimeWaiterRegistrationCanDetachBeforeSettlement() {
        OresFuture<Integer> future = new OresFuture<>();
        AtomicInteger callbacks = new AtomicInteger();

        OresFuture.RuntimeWaiterRegistration registration =
                future.whenCompleteRuntime((value, failure) -> callbacks.incrementAndGet());

        assertEquals(1, future.runtimeWaiterCountForTesting());
        assertTrue(registration.cancel());
        assertFalse(registration.cancel());
        assertEquals(0, future.runtimeWaiterCountForTesting());

        assertTrue(future.completeFromRuntime(7));
        assertEquals(0, callbacks.get());
    }

    @Test
    void raceDetachesWaiterFromNeverCompletingLoser() {
        OresFuture<Integer> winner = new OresFuture<>();
        OresFuture<Integer> never = new OresFuture<>();
        OresFuture<Integer> race = OresFutures.race(List.of(winner, never));

        assertEquals(1, never.runtimeWaiterCountForTesting());
        assertTrue(winner.completeFromRuntime(9));

        assertEquals(9, race.join());
        assertEquals(0, never.runtimeWaiterCountForTesting(),
                "completed race must not retain its continuation on a pending loser");
    }

}
