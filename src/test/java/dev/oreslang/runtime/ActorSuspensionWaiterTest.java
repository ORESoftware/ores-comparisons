package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSuspensionWaiterTest {

    @Test
    void stoppingSuspendedActorDetachesNeverCompletingFutureWaiter() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<Integer> never = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> ref =
                    runtime.<String>spawnPrivate(() -> (message, context) -> {
                        suspended.countDown();
                        context.suspendOn(
                                never,
                                (value, failure, resumedContext) ->
                                        fail("stopped actor must never resume its awaited continuation"));
                    });

            ref.send("suspend");
            assertTrue(suspended.await(2, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (never.runtimeWaiterCountForTesting() != 1
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, never.runtimeWaiterCountForTesting());

            ref.stop();

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (never.runtimeWaiterCountForTesting() != 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0, never.runtimeWaiterCountForTesting(),
                    "actor stop must detach its suspended continuation from the awaited Future");
            assertFalse(ref.isAlive());
        }
    }
}
