package dev.oreslang.runtime;

import dev.oreslang.runtime.ActorRuntime.ActorAffinityPolicy;
import dev.oreslang.runtime.ActorRuntime.AffinityBlockingQueue;
import dev.oreslang.runtime.ActorRuntime.AffinityTask;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCarrierAffinityTest {

    @Test
    void dispatcherPolicyIsSoftLastCarrierAffinity() {
        assertEquals(
                ActorAffinityPolicy.PREFER_LAST_CARRIER,
                ActorRuntime.DispatcherConfig.defaultsForProcessors(8).actorAffinityPolicy());
    }

    @Test
    void queuePrefersCurrentCarrierAheadOfForeignAffineWork() throws Exception {
        AffinityBlockingQueue queue = new AffinityBlockingQueue(8);
        CountDownLatch releaseForeign = new CountDownLatch(1);
        Thread foreignCarrier = Thread.ofPlatform().daemon(true).start(() -> {
            try {
                releaseForeign.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            AffinityTask foreign = new AffinityTask(foreignCarrier, () -> { });
            AffinityTask local = new AffinityTask(Thread.currentThread(), () -> { });

            assertTrue(queue.offer(foreign));
            assertTrue(queue.offer(local));

            assertSame(
                    local,
                    queue.poll(),
                    "a carrier should select its own actor turn before stealing a foreign-affine turn");
            assertSame(foreign, queue.poll());
        } finally {
            releaseForeign.countDown();
            foreignCarrier.join(2_000);
            assertFalse(foreignCarrier.isAlive());
        }
    }

    @Test
    void staleCarrierPreferenceDoesNotStrandRunnableWork() {
        AffinityBlockingQueue queue = new AffinityBlockingQueue(4);
        Thread neverStarted = Thread.ofPlatform().unstarted(() -> { });
        AffinityTask stale = new AffinityTask(neverStarted, () -> { });

        assertTrue(queue.offer(stale));
        assertSame(
                stale,
                queue.poll(),
                "a retired/not-live preferred carrier must be treated as immediately stealable");
    }

    @Test
    void foreignWorkerCanStealAfterAffinityGrace() throws Exception {
        AffinityBlockingQueue queue = new AffinityBlockingQueue(4);
        CountDownLatch releaseHome = new CountDownLatch(1);
        Thread homeCarrier = Thread.ofPlatform().daemon(true).start(() -> {
            try {
                releaseHome.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        AffinityTask task = new AffinityTask(homeCarrier, () -> { });
        assertTrue(queue.offer(task));

        AtomicReference<Runnable> stolen = new AtomicReference<>();
        Thread thief = Thread.ofPlatform().daemon(true).start(() -> {
            try {
                stolen.set(queue.take());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            thief.join(2_000);
            assertFalse(
                    thief.isAlive(),
                    "soft affinity must never permanently reserve work for a busy preferred carrier");
            assertSame(task, stolen.get());
        } finally {
            releaseHome.countDown();
            homeCarrier.join(2_000);
        }
    }

    @Test
    void actorBuildsAffinityHitsAcrossBoundedMailboxTurns() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                2,
                2,
                2,
                1,
                TimeUnit.SECONDS.toNanos(1),
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int messages = 48;
            CountDownLatch delivered = new CountDownLatch(messages);
            Set<String> carriers = java.util.concurrent.ConcurrentHashMap.newKeySet();

            var actor = runtime.<Integer>spawnSharedTrusted(ignored -> (message, turn) -> {
                carriers.add(Thread.currentThread().getName());
                delivered.countDown();
            });

            for (int i = 0; i < messages; i++) actor.send(i);

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            var affinity = runtime.dispatcherAffinityStats(ActorRuntime.ActorKind.SHARED);
            assertTrue(
                    affinity.affinityHits() > 0,
                    "later mailbox batches should reuse a preferred shared-actor carrier when available");
            assertFalse(carriers.isEmpty());

            actor.stop();
        }
    }
}
