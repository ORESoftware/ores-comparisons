package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AwaitSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class AwaitReceiveHardeningTest {
    @Test
    void awaitUnwrapsCompletionStagesAndVirtualThreadTaskResults() throws Exception {
        assertEquals(42, AwaitSupport.await(CompletableFuture.completedFuture(42)));

        FutureTask<Integer> task = new FutureTask<>(() -> 7 * 6);
        Thread carrier = Thread.ofVirtual().name("ores-await-test").start(task);
        assertEquals(42, AwaitSupport.await(task));
        carrier.join();

        CountDownLatch ran = new CountDownLatch(1);
        Thread completionOnly = Thread.ofVirtual().start(ran::countDown);
        assertNull(AwaitSupport.await(completionOnly));
        assertEquals(0L, ran.getCount());
    }

    @Test
    void awaitPropagatesTaskFailureWithoutCompletionWrapperNoise() {
        CompletableFuture<Integer> failed = new CompletableFuture<>();
        IllegalArgumentException cause = new IllegalArgumentException("boom");
        failed.completeExceptionally(cause);

        IllegalArgumentException observed = assertThrows(
                IllegalArgumentException.class,
                () -> AwaitSupport.await(failed));
        assertSame(cause, observed);
    }

    @Test
    void pendingHostAwaitCannotParkAnActorDispatcherCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try {
                    AwaitSupport.await(new CompletableFuture<>());
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, observed.get());
            assertTrue(observed.get().getMessage().contains("dispatcher carrier"));
        }
    }

    @Test
    void suspendingReceiveConsumesNextMessageInsteadOfRedispatchingIt() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<String> observed = new AtomicReference<>();
            AtomicInteger ordinaryTurns = new AtomicInteger();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                ordinaryTurns.incrementAndGet();
                if ("arm".equals(message)) {
                    context.receive().thenAccept(next -> {
                        observed.set(next);
                        received.countDown();
                    });
                    armed.countDown();
                }
            });

            ref.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            ref.send("payload");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals("payload", observed.get());
            assertEquals(1, ordinaryTurns.get(),
                    "the receive waiter, not a second ordinary mailbox callback, owns the next FIFO message");
        }
    }

    @Test
    void tryReceivePollsOnlyTheCurrentMailboxHead() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch pollNow = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ReceiveResult<String>> result = new AtomicReference<>();
            AtomicInteger ordinaryTurns = new AtomicInteger();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                ordinaryTurns.incrementAndGet();
                if ("poll".equals(message)) {
                    entered.countDown();
                    assertTrue(pollNow.await(2, TimeUnit.SECONDS));
                    result.set(context.tryReceive());
                    finished.countDown();
                }
            });

            ref.send("poll");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ref.send("next");
            pollNow.countDown();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertNotNull(result.get());
            assertTrue(result.get().received());
            assertEquals("next", result.get().message());
            assertEquals(1, ordinaryTurns.get());
        }
    }

    @Test
    void tryReceiveReturnsEmptyWithoutWaiting() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ReceiveResult<String>> result = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                result.set(context.tryReceive());
                finished.countDown();
            });

            ref.send("poll-empty");
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(result.get().received());
            assertNull(result.get().message());
        }
    }


    @Test
    void awaitSupportsRuntimeDefinedAwaitablesAndRejectsNonAwaitables() {
        AwaitSupport.Awaitable<Integer> custom = new AwaitSupport.Awaitable<>() {
            @Override public boolean isDone() { return true; }
            @Override public Integer await() { return 42; }
        };

        assertEquals(42, AwaitSupport.await(custom));
        assertThrows(IllegalArgumentException.class, () -> AwaitSupport.await(null));
        assertThrows(IllegalArgumentException.class, () -> AwaitSupport.await("not-awaitable"));
    }

    @Test
    void cancelledReceiveDoesNotStealTheNextMailboxMessage() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<String> ordinary = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                if ("arm".equals(message)) {
                    var waiter = context.receive().toCompletableFuture();
                    assertTrue(waiter.cancel(true));
                    armed.countDown();
                    return;
                }
                ordinary.set(message);
                delivered.countDown();
            });

            ref.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            ref.send("payload");

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals("payload", ordinary.get(),
                    "cancelling a receive must restore ordinary FIFO delivery for the next message");
        }
    }

    @Test
    void receiveAPIsAreActorTurnScopedAndOneWaiterAtATime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch armed = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorContext<String>> captured = new AtomicReference<>();
            AtomicReference<Throwable> duplicate = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                captured.set(context);
                context.receive();
                try {
                    context.receive();
                } catch (Throwable failure) {
                    duplicate.set(failure);
                }
                armed.countDown();
            });

            ref.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, duplicate.get());
            assertThrows(IllegalStateException.class, () -> captured.get().tryReceive());

            ref.stop();
        }
    }
}
