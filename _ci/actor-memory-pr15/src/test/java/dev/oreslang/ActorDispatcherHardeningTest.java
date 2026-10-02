package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorDispatcherHardeningTest {

    @Test
    void actorIdsArePrefixedUuidStringsAndLocalSequencesAreMonotonic() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.<String>spawnPrivate(factory -> (message, context) -> { });
            var second = runtime.<String>spawnPrivate(factory -> (message, context) -> { });

            assertTrue(first.id().value().startsWith("actor_id_"));
            assertTrue(second.id().value().startsWith("actor_id_"));
            assertDoesNotThrow(() -> UUID.fromString(first.id().value().substring("actor_id_".length())));
            assertDoesNotThrow(() -> UUID.fromString(second.id().value().substring("actor_id_".length())));
            assertNotEquals(first.id(), second.id());
            assertEquals(first.localSequence() + 1L, second.localSequence());

            // Preserve the Java embedding compatibility constructor while making
            // the public representation the prefixed string form.
            UUID raw = UUID.randomUUID();
            assertEquals("actor_id_" + raw, new ActorRuntime.ActorId(raw).value());
        }
    }

    @Test
    void untrustedActorsFailClosedWithoutHardIsolationBoundary() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnUntrusted(
                            factory -> (message, context) -> context.self().stop()));
            assertTrue(denied.getMessage().contains("hard sandbox/isolate boundary"));
        }
    }

    @Test
    void allThreeActorKindsUseDistinctDispatcherBulkheads() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 1, 1, 64);
        ActorRuntime.TurnExecutor contained = new ActorRuntime.TurnExecutor() {
            @Override
            public void execute(Runnable turn) {
                turn.run();
            }

            @Override
            public boolean supportsUntrustedIsolation() {
                return true;
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config, contained)) {
            var shared = runtime.<String>spawnShared(
                    factory -> (message, context) -> {
                        throw new IllegalStateException(Thread.currentThread().getName());
                    });
            var privateRef = runtime.<String>spawnPrivate(
                    factory -> (message, context) -> {
                        throw new IllegalStateException(Thread.currentThread().getName());
                    });
            var untrusted = runtime.<String>spawnUntrusted(
                    factory -> (message, context) -> {
                        assertTrue(context.privateMemory().isPresent());
                        throw new IllegalStateException(Thread.currentThread().getName());
                    });

            shared.send("go");
            privateRef.send("go");
            untrusted.send("go");

            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(privateRef.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(untrusted.awaitTermination(2, TimeUnit.SECONDS));

            assertTrue(shared.failure().orElseThrow().getMessage()
                    .startsWith("ores-shared-actor-dispatcher-"));
            assertTrue(privateRef.failure().orElseThrow().getMessage()
                    .startsWith("ores-private-actor-dispatcher-"));
            assertTrue(untrusted.failure().orElseThrow().getMessage()
                    .startsWith("ores-untrusted-actor-dispatcher-"));

            assertEquals(1, runtime.dispatcherSnapshot(ActorRuntime.ActorKind.SHARED).parallelism());
            assertEquals(1, runtime.dispatcherSnapshot(ActorRuntime.ActorKind.PRIVATE).parallelism());
            assertEquals(1, runtime.dispatcherSnapshot(ActorRuntime.ActorKind.UNTRUSTED).parallelism());
        }
    }

    @Test
    void throughputOneRequeuesHotActorBehindAlreadyReadyPeer() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 1, 1, 64);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            List<String> order = new CopyOnWriteArrayList<>();
            CountDownLatch hotEntered = new CountDownLatch(1);
            CountDownLatch releaseHot = new CountDownLatch(1);

            var hot = runtime.<String>spawnPrivateTrusted(factory -> (message, context) -> {
                order.add(message);
                if (message.equals("hot-0")) {
                    hotEntered.countDown();
                    if (!releaseHot.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release hot actor");
                    }
                }
                if (message.equals("hot-2")) context.self().stop();
            });
            var peer = runtime.<String>spawnPrivateTrusted(factory -> (message, context) -> {
                order.add(message);
                context.self().stop();
            });

            hot.send("hot-0");
            assertTrue(hotEntered.await(2, TimeUnit.SECONDS));

            hot.send("hot-1");
            hot.send("hot-2");
            peer.send("peer-0");
            releaseHot.countDown();

            assertTrue(peer.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(hot.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(peer.failure().isEmpty());
            assertTrue(hot.failure().isEmpty());

            int peerIndex = order.indexOf("peer-0");
            int hotSecondIndex = order.indexOf("hot-1");
            assertTrue(peerIndex >= 0);
            assertTrue(hotSecondIndex >= 0);
            assertTrue(peerIndex < hotSecondIndex,
                    "bounded throughput must put a hot actor back behind an already-ready peer: " + order);
        }
    }

    @Test
    void actorOriginatedSynchronousGroupFanoutCannotExceedOneSchedulingQuantum() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 1, 1, 64);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var a = runtime.<String>spawnPrivate(factory -> (message, context) -> { });
            var b = runtime.<String>spawnPrivate(factory -> (message, context) -> { });
            var group = runtime.<String>group("wide").add(a).add(b);

            var broadcaster = runtime.<String>spawnPrivateTrusted(factory -> (message, context) ->
                    group.broadcast(message));

            broadcaster.send("fanout");
            assertTrue(broadcaster.awaitTermination(2, TimeUnit.SECONDS));
            Throwable failure = broadcaster.failure().orElseThrow();
            assertTrue(failure.getMessage().contains("synchronous broadcast exceeds per-turn fanout budget"));

            a.stop();
            b.stop();
        }
    }

    @Test
    void actorGroupsPruneFinalizedMembersWithoutRequiringBroadcast() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(factory -> (message, context) -> { });
            var group = runtime.<String>group("workers").add(ref);

            assertEquals(1, group.size());
            ref.stop();
            assertEquals(0, group.size());
        }
    }

    @Test
    void closingRuntimeInvalidatesActorGroups() {
        ActorRuntime runtime = new ActorRuntime();
        var group = runtime.<String>group("workers");
        runtime.close();

        assertTrue(group.closed());
        assertThrows(IllegalStateException.class, group::size);
    }
}
