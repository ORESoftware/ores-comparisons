package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.oreslang.runtime.IsolatePolicy;

import static org.junit.jupiter.api.Assertions.*;

final class ActorInvocationRuntimeTest {

    @Test
    void oneShotInvocationSelectsSharedOrPrivateMemoryMode() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.MemoryMode shared = runtime.invoke(
                    "ping",
                    ActorRuntime.MemoryPolicy.sharedHeap(),
                    (message, context) -> context.memory().mode());

            ActorRuntime.MemoryMode isolated = runtime.invoke(
                    "ping",
                    ActorRuntime.MemoryPolicy.privateArena(1024L * 1024L),
                    (message, context) -> {
                        assertTrue(Thread.currentThread().isVirtual());
                        assertTrue(context.memory().capacityBytes() >= 1024L * 1024L);
                        return context.memory().mode();
                    });

            assertEquals(ActorRuntime.MemoryMode.SHARED_HEAP, shared);
            assertEquals(ActorRuntime.MemoryMode.PRIVATE_ARENA, isolated);
        }
    }

    @Test
    void oneShotInvocationPropagatesFailureToCaller() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.invoke(
                            "boom",
                            ActorRuntime.MemoryPolicy.sharedHeap(),
                            (message, context) -> {
                                throw new IllegalStateException(message);
                            }));

            assertEquals("boom", failure.getMessage());
        }
    }

    @Test
    void oneShotInvocationSurfacesPrivateArenaStartupFailureInsteadOfHanging() throws Exception {
        long mib = 1024L * 1024L;
        IsolatePolicy policy = new IsolatePolicy(
                Set.of(),
                16 * mib,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            CountDownLatch reserved = new CountDownLatch(1);
            runtime.<String>spawn(
                    policy,
                    ActorRuntime.MemoryPolicy.privateArena(16 * mib),
                    () -> {
                        reserved.countDown();
                        return (message, context) -> { };
                    });

            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            OutOfMemoryError failure = assertThrows(
                    OutOfMemoryError.class,
                    () -> runtime.invoke(
                            "cannot-start",
                            ActorRuntime.MemoryPolicy.privateArena(mib),
                            (message, context) -> message));

            assertTrue(failure.getMessage().contains("tenant private arena budget exhausted"));
        }
    }

    @Test
    void oneShotInvocationFreezesItsReturnValue() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Object value = runtime.invoke(
                    "ok",
                    ActorRuntime.MemoryPolicy.sharedHeap(),
                    (message, context) -> new StringBuilder(message));

            fail("mutable host return should have been rejected, got " + value);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not Sendable"));
        }
    }
}
