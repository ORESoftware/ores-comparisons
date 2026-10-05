package dev.oreslang.runtime;

import dev.oreslang.runtime.ActorRuntime.ActorAffinityPolicy;
import dev.oreslang.runtime.ActorRuntime.AffinityBlockingQueue;
import dev.oreslang.runtime.ActorRuntime.AffinityWork;
import dev.oreslang.runtime.ActorRuntime.AffinityWorkClass;
import dev.oreslang.runtime.ActorRuntime.CarrierRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCarrierAffinityTest {

    private static final class TestWork implements AffinityWork {
        private final CarrierRegistry registry;
        private final Runnable action;
        private final AffinityWorkClass workClass;
        private volatile long preferred;
        private volatile long enqueuedNanos;

        private TestWork(CarrierRegistry registry, long preferred, Runnable action) {
            this(registry, AffinityWorkClass.ACTOR_TURN, preferred, action);
        }

        private TestWork(
                CarrierRegistry registry,
                AffinityWorkClass workClass,
                long preferred,
                Runnable action) {
            this.registry = registry;
            this.workClass = workClass;
            this.preferred = preferred;
            this.action = action;
        }

        @Override public CarrierRegistry affinityRegistry() { return registry; }
        @Override public AffinityWorkClass affinityWorkClass() { return workClass; }
        @Override public long preferredCarrierToken() { return preferred; }
        @Override public long affinityEnqueuedNanos() { return enqueuedNanos; }
        @Override public void affinityEnqueuedNanos(long value) { enqueuedNanos = value; }
        @Override public void run() { action.run(); }
    }

    @Test
    void dispatcherPolicyIsSoftLastCarrierAffinity() {
        assertEquals(
                ActorAffinityPolicy.PREFER_LAST_CARRIER,
                ActorRuntime.DispatcherConfig.defaultsForProcessors(8).actorAffinityPolicy());
    }

    @Test
    void pathologicalCarrierCountsAreRejectedBeforeDenseMetadataAllocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorRuntime.DispatcherConfig(
                        ActorRuntime.DispatcherConfig.HARD_MAX_POOL_THREADS,
                        1,
                        1,
                        64,
                        TimeUnit.MILLISECONDS.toNanos(2),
                        TimeUnit.MILLISECONDS.toNanos(250),
                        1,
                        64));
    }

    @Test
    void carrierTokensAreGenerationSafeAcrossSlotReuse() throws Exception {
        CarrierRegistry registry = new CarrierRegistry(1);
        AtomicLong first = new AtomicLong();
        AtomicLong second = new AtomicLong();

        Thread one = Thread.ofPlatform().start(
                registry.bindWorker(() -> first.set(registry.currentToken())));
        one.join(2_000);
        assertFalse(one.isAlive());
        assertNotEquals(0L, first.get());
        assertFalse(registry.isLive(first.get()));

        Thread two = Thread.ofPlatform().start(
                registry.bindWorker(() -> second.set(registry.currentToken())));
        two.join(2_000);
        assertFalse(two.isAlive());

        assertNotEquals(0L, second.get());
        assertNotEquals(
                first.get(),
                second.get(),
                "reusing a carrier slot must mint a new generation token");
        assertFalse(registry.isLive(first.get()));
        assertFalse(registry.isLive(second.get()),
                "token must retire when its worker exits");
    }

    @Test
    void actorQueueRejectsDataOrientedCpuAndGpuWork() {
        CarrierRegistry registry = new CarrierRegistry(1);
        AffinityBlockingQueue queue = new AffinityBlockingQueue(4, registry);

        var cpuChunk = new TestWork(
                registry,
                AffinityWorkClass.CPU_DATA_CHUNK,
                0L,
                () -> { });
        var gpuKernel = new TestWork(
                registry,
                AffinityWorkClass.GPU_KERNEL,
                0L,
                () -> { });

        IllegalArgumentException cpuFailure =
                assertThrows(IllegalArgumentException.class, () -> queue.offer(cpuChunk));
        assertTrue(cpuFailure.getMessage().contains("heterogeneous compute/dataflow scheduler"));

        IllegalArgumentException gpuFailure =
                assertThrows(IllegalArgumentException.class, () -> queue.offer(gpuKernel));
        assertTrue(gpuFailure.getMessage().contains("heterogeneous compute/dataflow scheduler"));
        assertTrue(queue.isEmpty());
    }

    @Test
    void queuePrefersCurrentCarrierAheadOfYoungForeignAffineWork() throws Exception {
        CarrierRegistry registry = new CarrierRegistry(2);
        AffinityBlockingQueue queue = new AffinityBlockingQueue(8, registry);
        CountDownLatch foreignReady = new CountDownLatch(1);
        CountDownLatch localReady = new CountDownLatch(1);
        CountDownLatch releaseForeign = new CountDownLatch(1);
        CountDownLatch releaseLocal = new CountDownLatch(1);
        AtomicLong foreignToken = new AtomicLong();
        AtomicLong localToken = new AtomicLong();
        AtomicReference<Runnable> selected = new AtomicReference<>();

        Thread foreign = Thread.ofPlatform().start(registry.bindWorker(() -> {
            foreignToken.set(registry.currentToken());
            foreignReady.countDown();
            await(releaseForeign);
        }));
        Thread local = Thread.ofPlatform().start(registry.bindWorker(() -> {
            localToken.set(registry.currentToken());
            localReady.countDown();
            await(releaseLocal);
            selected.set(queue.poll());
        }));

        try {
            assertTrue(foreignReady.await(2, TimeUnit.SECONDS));
            assertTrue(localReady.await(2, TimeUnit.SECONDS));

            TestWork foreignWork = new TestWork(registry, foreignToken.get(), () -> { });
            TestWork localWork = new TestWork(registry, localToken.get(), () -> { });

            assertTrue(queue.offer(foreignWork));
            assertTrue(queue.offer(localWork));

            releaseLocal.countDown();
            local.join(2_000);
            assertFalse(local.isAlive());
            assertSame(
                    localWork,
                    selected.get(),
                    "a carrier may prefer its own young work over a young foreign-affine head");
            assertSame(foreignWork, queue.poll());
        } finally {
            releaseLocal.countDown();
            releaseForeign.countDown();
            local.join(2_000);
            foreign.join(2_000);
        }
    }

    @Test
    void actorAffinityQueueRejectsUnclassifiedCpuDataChunksAndGpuKernels() {
        CarrierRegistry registry = new CarrierRegistry(1);
        AffinityBlockingQueue queue = new AffinityBlockingQueue(4, registry);

        IllegalArgumentException rawFailure = assertThrows(
                IllegalArgumentException.class,
                () -> queue.offer(() -> { }));
        assertTrue(rawFailure.getMessage().contains("ACTOR_TURN"));
        assertTrue(queue.isEmpty());

        for (AffinityWorkClass workClass : new AffinityWorkClass[] {
                AffinityWorkClass.CPU_DATA_CHUNK,
                AffinityWorkClass.GPU_KERNEL
        }) {
            AffinityWork computeWork = new AffinityWork() {
                private volatile long enqueued;

                @Override public CarrierRegistry affinityRegistry() { return registry; }
                @Override public AffinityWorkClass affinityWorkClass() { return workClass; }
                @Override public long preferredCarrierToken() { return 0L; }
                @Override public long affinityEnqueuedNanos() { return enqueued; }
                @Override public void affinityEnqueuedNanos(long value) { enqueued = value; }
                @Override public void run() { }
            };

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> queue.offer(computeWork));
            assertTrue(failure.getMessage().contains("heterogeneous compute/dataflow scheduler"));
            assertTrue(queue.isEmpty());

            IllegalArgumentException timedFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> queue.offer(computeWork, 1, TimeUnit.MILLISECONDS));
            assertTrue(timedFailure.getMessage().contains("heterogeneous compute/dataflow scheduler"));
            assertTrue(queue.isEmpty());

            IllegalArgumentException putFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> queue.put(computeWork));
            assertTrue(putFailure.getMessage().contains("heterogeneous compute/dataflow scheduler"));
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    void unboundHeadWinsOverAffinityToPreserveFirstRunFairness() throws Exception {
        CarrierRegistry registry = new CarrierRegistry(1);
        AffinityBlockingQueue queue = new AffinityBlockingQueue(8, registry);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong token = new AtomicLong();
        AtomicReference<Runnable> selected = new AtomicReference<>();

        Thread worker = Thread.ofPlatform().start(registry.bindWorker(() -> {
            token.set(registry.currentToken());
            ready.countDown();
            await(release);
            try {
                selected.set(queue.take());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        TestWork peerFirstRun = new TestWork(registry, 0L, () -> { });
        TestWork hotLocalActor = new TestWork(registry, token.get(), () -> { });
        assertTrue(queue.offer(peerFirstRun));
        assertTrue(queue.offer(hotLocalActor));

        release.countDown();
        worker.join(2_000);

        assertFalse(worker.isAlive());
        assertSame(
                peerFirstRun,
                selected.get(),
                "affinity must not let a hot actor bypass a peer that has never run");
    }

    @Test
    void foreignWorkerCanStealAfterBoundedAffinityAge() throws Exception {
        CarrierRegistry registry = new CarrierRegistry(2);
        AffinityBlockingQueue queue = new AffinityBlockingQueue(4, registry);
        CountDownLatch homeReady = new CountDownLatch(1);
        CountDownLatch releaseHome = new CountDownLatch(1);
        AtomicLong homeToken = new AtomicLong();

        Thread home = Thread.ofPlatform().start(registry.bindWorker(() -> {
            homeToken.set(registry.currentToken());
            homeReady.countDown();
            await(releaseHome);
        }));

        assertTrue(homeReady.await(2, TimeUnit.SECONDS));
        TestWork task = new TestWork(registry, homeToken.get(), () -> { });
        assertTrue(queue.offer(task));

        AtomicReference<Runnable> stolen = new AtomicReference<>();
        Thread thief = Thread.ofPlatform().start(registry.bindWorker(() -> {
            try {
                stolen.set(queue.take());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));

        try {
            thief.join(2_000);
            assertFalse(
                    thief.isAlive(),
                    "soft affinity must never reserve runnable work for a busy home carrier");
            assertSame(task, stolen.get());
        } finally {
            releaseHome.countDown();
            home.join(2_000);
        }
    }

    @Test
    void staleCarrierTokenIsImmediatelyStealable() throws Exception {
        CarrierRegistry registry = new CarrierRegistry(1);
        AtomicLong stale = new AtomicLong();

        Thread retired = Thread.ofPlatform().start(
                registry.bindWorker(() -> stale.set(registry.currentToken())));
        retired.join(2_000);
        assertFalse(registry.isLive(stale.get()));

        AffinityBlockingQueue queue = new AffinityBlockingQueue(4, registry);
        TestWork staleWork = new TestWork(registry, stale.get(), () -> { });
        assertTrue(queue.offer(staleWork));

        assertSame(
                staleWork,
                queue.poll(),
                "a retired generation token must never strand runnable work");
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
