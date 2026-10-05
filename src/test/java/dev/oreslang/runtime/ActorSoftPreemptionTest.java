package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSoftPreemptionTest {
    private static ActorRuntime.DispatcherConfig singleCarrierConfig() {
        return new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                Long.MAX_VALUE,
                1024);
    }

    @Test
    void explicitSchedulerRequestPreemptsCpuBoundActorWithoutAwait() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CountDownLatch hotEntered = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);
            CountDownLatch hotFinished = new CountDownLatch(1);
            AtomicBoolean beginCheckpoints = new AtomicBoolean();
            AtomicBoolean finish = new AtomicBoolean();
            AtomicReference<ActorRuntime.ActorContinuation> loop = new AtomicReference<>();

            loop.set((value, failure, actorContext) -> {
                assertNull(value);
                assertNull(failure);
                while (!finish.get()) {
                    actorContext.checkpoint(loop.get());
                }
                hotFinished.countDown();
            });

            var hot = runtime.<String>spawnPrivate(context -> (message, actorContext) -> {
                hotEntered.countDown();
                // Deliberately demonstrate the contract: the scheduler can ask
                // at any time, but cannot safely tear through code until the
                // compiler reaches a resumable checkpoint.
                while (!beginCheckpoints.get()) Thread.onSpinWait();
                loop.get().resume(null, null, actorContext);
            });
            var peer = runtime.<String>spawnPrivate(context -> (message, actorContext) -> {
                peerRan.countDown();
            });

            hot.send("spin");
            assertTrue(hotEntered.await(2, TimeUnit.SECONDS));
            peer.send("peer");
            assertFalse(
                    peerRan.await(50, TimeUnit.MILLISECONDS),
                    "without a safepoint the scheduler must not use unsafe asynchronous stack suspension");

            assertTrue(runtime.requestPreemption(hot));
            beginCheckpoints.set(true);

            assertTrue(
                    peerRan.await(2, TimeUnit.SECONDS),
                    "requested preemption must release the only carrier so a peer can run");
            assertTrue(
                    runtime.cooperativePreemptionCount(ActorRuntime.ActorKind.PRIVATE) > 0,
                    "runtime must account the cooperative scheduler handoff");

            finish.set(true);
            assertTrue(hotFinished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void reductionBudgetEventuallyPreemptsHotActorWithoutAwait() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CountDownLatch hotEntered = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);
            CountDownLatch hotFinished = new CountDownLatch(1);
            AtomicBoolean finish = new AtomicBoolean();
            AtomicReference<ActorRuntime.ActorContinuation> loop = new AtomicReference<>();

            loop.set((value, failure, actorContext) -> {
                assertNull(value);
                assertNull(failure);
                while (!finish.get()) {
                    actorContext.checkpoint(loop.get());
                }
                hotFinished.countDown();
            });

            var hot = runtime.<String>spawnPrivate(context -> (message, actorContext) -> {
                hotEntered.countDown();
                loop.get().resume(null, null, actorContext);
            });
            var peer = runtime.<String>spawnPrivate(context -> (message, actorContext) -> {
                peerRan.countDown();
            });

            hot.send("spin");
            assertTrue(hotEntered.await(2, TimeUnit.SECONDS));
            peer.send("peer");

            assertTrue(
                    peerRan.await(2, TimeUnit.SECONDS),
                    "BEAM-style reduction exhaustion must requeue a CPU-bound actor even without await");
            assertTrue(
                    runtime.cooperativePreemptionCount(ActorRuntime.ActorKind.PRIVATE) > 0);

            finish.set(true);
            assertTrue(hotFinished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void pendingPreemptionWaitsForCarrierPinnedReadGuardToClose() throws Exception {
        OresRwLock<Integer> lock = new OresRwLock<>(7);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CountDownLatch guardAcquired = new CountDownLatch(1);
            CountDownLatch insideGuardCheckpointReturned = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            AtomicBoolean enterCheckpoint = new AtomicBoolean();
            AtomicBoolean guardOpen = new AtomicBoolean();
            AtomicReference<ActorRuntime.ActorContinuation> resume = new AtomicReference<>();

            resume.set((value, failure, actorContext) -> {
                assertNull(value);
                assertNull(failure);
                assertFalse(
                        guardOpen.get(),
                        "carrier-pinned RW-lock guard must not cross scheduler migration");
                assertFalse(
                        Thread.currentThread().isInterrupted(),
                        "soft preemption must not leak interrupt state onto the resumed carrier");
                resumed.countDown();
                actorContext.self().stop();
            });

            ActorRuntime.ActorRef<OresRwLock<Integer>> actor =
                    runtime.spawnShared(context -> (incoming, actorContext) -> {
                        OresRwLock.ReadGuard<Integer> read = incoming.readLock();
                        guardOpen.set(true);
                        try {
                            guardAcquired.countDown();
                            while (!enterCheckpoint.get()) Thread.onSpinWait();

                            actorContext.checkpoint(resume.get());
                            insideGuardCheckpointReturned.countDown();
                        } finally {
                            guardOpen.set(false);
                            read.close();
                        }

                        // The request remained pending while the carrier-pinned
                        // guard was open and must be serviced immediately at
                        // the next safe checkpoint after close.
                        actorContext.checkpoint(resume.get());
                        fail("pending scheduler preemption must unwind this carrier turn");
                    });

            actor.send(lock);
            assertTrue(guardAcquired.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.requestPreemption(actor));
            enterCheckpoint.set(true);

            assertTrue(
                    insideGuardCheckpointReturned.await(2, TimeUnit.SECONDS),
                    "preemption must be deferred while a thread-affine guard is live");
            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertTrue(
                    runtime.cooperativePreemptionCount(ActorRuntime.ActorKind.SHARED) > 0);
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(),
                    () -> "actor failed: " + actor.failure().orElse(null));
        }
    }
}
