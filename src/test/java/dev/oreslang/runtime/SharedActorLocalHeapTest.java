package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

final class SharedActorLocalHeapTest {
    @Test
    void sharedActorGetsLocalMemoryWithoutGainingPrivateMemory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> local = new AtomicReference<>();
            AtomicReference<ActorRuntime.MemoryReservation> reservation = new AtomicReference<>();

            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                assertFalse(context.privateMemory().isPresent());
                ActorRuntime.ActorMemorySlice memory = context.localMemory().orElseThrow();
                assertEquals(ActorRuntime.ActorKind.SHARED, memory.kind());
                assertEquals(context.self().id(), memory.owner());
                local.set(memory);
                reservation.set(memory.reserveHeap(4096));
                observed.countDown();
            });

            ref.send("reserve");
            assertTrue(observed.await(2, TimeUnit.SECONDS));

            assertEquals(ref.id(), local.get().owner());
            assertTrue(local.get().usedBytes() >= 4096);
            assertEquals(0L, runtime.privateMemoryBytes());
            assertTrue(runtime.sharedActorLocalMemoryBytes() >= 4096);
            assertEquals(runtime.sharedActorLocalMemoryBytes(), runtime.actorLocalMemoryBytes());

            ref.stop();

            assertTrue(local.get().closed());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
            assertEquals(0L, runtime.actorLocalMemoryBytes());

            // Actor teardown owns the domain lifetime; late reservation release is idempotent.
            reservation.get().close();
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void explicitSharedStateStaysOutsideSharedActorLocalDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.syncCell("shared-value");
            long explicitSharedBytes = runtime.sharedMemoryBytes();
            assertTrue(explicitSharedBytes > 0L);

            CountDownLatch observed = new CountDownLatch(1);
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                context.localMemory().orElseThrow().reserveHeap(2048);
                observed.countDown();
            });

            ref.send("reserve");
            assertTrue(observed.await(2, TimeUnit.SECONDS));

            assertTrue(runtime.sharedActorLocalMemoryBytes() >= 2048);
            assertEquals(explicitSharedBytes, runtime.sharedMemoryBytes(),
                    "actor-local reservations must not be charged as explicit shared memory");
            assertEquals(
                    runtime.actorLocalMemoryBytes() + runtime.sharedMemoryBytes(),
                    runtime.actorMemoryBytes());

            ref.stop();

            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
            assertEquals(explicitSharedBytes, runtime.sharedMemoryBytes(),
                    "stopping one shared actor must not reclaim runtime-wide shared state");
            assertEquals("shared-value", shared.snapshot());
        }
    }

    @Test
    void localMemoryRemainsOwnerCheckedAndPrivateCompatibilityIsPreserved() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch sharedCaptured = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> leakedShared = new AtomicReference<>();

            var shared = runtime.<String>spawnShared(() -> (message, context) -> {
                leakedShared.set(context.localMemory().orElseThrow());
                sharedCaptured.countDown();
            });
            shared.send("capture");
            assertTrue(sharedCaptured.await(2, TimeUnit.SECONDS));

            IllegalStateException outsideOwner = assertThrows(
                    IllegalStateException.class,
                    () -> leakedShared.get().reserveHeap(1));
            assertTrue(outsideOwner.getMessage().contains("owning actor"));

            CountDownLatch privateObserved = new CountDownLatch(1);
            var isolated = runtime.<String>spawnPrivate(() -> (message, context) -> {
                assertSame(
                        context.privateMemory().orElseThrow(),
                        context.localMemory().orElseThrow());
                assertEquals(ActorRuntime.ActorKind.PRIVATE, context.localMemory().orElseThrow().kind());
                privateObserved.countDown();
            });
            isolated.send("check");
            assertTrue(privateObserved.await(2, TimeUnit.SECONDS));
        }
    }
}
