package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class GarbageCollectionHardeningTest {

    @Test
    void actorGcIsActorLocalAndPeriodicCollectionNeverEscalatesToProcessGc() throws Exception {
        RecordingCollector collector = new RecordingCollector();
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                config,
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(1, 0, 0),
                collector)) {
            var ref = runtime.<String>spawnPrivate(factory -> (message, context) -> {
                context.gc();
                context.self().stop();
            });

            ref.send("collect");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());

            assertTrue(collector.actorCollections.stream().allMatch(event ->
                    event.actorId().equals(ref.id()) && event.kind() == ActorRuntime.ActorKind.PRIVATE));
            assertTrue(collector.actorCollections.stream().anyMatch(event ->
                    event.reason() == ActorRuntime.GcReason.EXPLICIT));
            assertTrue(collector.actorCollections.stream().anyMatch(event ->
                    event.reason() == ActorRuntime.GcReason.PERIODIC));
            assertTrue(collector.processCollections.isEmpty());
            assertEquals(1, runtime.gcStats().actorRequests());
            assertEquals(0, runtime.gcStats().processRequests());
        }
    }

    @Test
    void processGcIsCapabilityGatedAndPrivateActorsCannotTriggerIt() throws Exception {
        RecordingCollector collector = new RecordingCollector();
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                config,
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(0, 0, 0),
                collector)) {
            runtime.gcProcess();
            assertEquals(List.of(ActorRuntime.GcReason.EXPLICIT), collector.processCollections);

            var ref = runtime.<String>spawnPrivate(factory -> (message, context) -> {
                SecurityException denied = assertThrows(
                        SecurityException.class,
                        () -> context.runtime().gcProcess());
                assertTrue(denied.getMessage().contains("PROCESS_GC"));
                context.gc();
                context.self().stop();
            });
            ref.send("check");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }

        RecordingCollector deniedCollector = new RecordingCollector();
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.strictFaas(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(0, 0, 0),
                deniedCollector)) {
            assertThrows(SecurityException.class, runtime::gcProcess);
            assertTrue(deniedCollector.processCollections.isEmpty());
        }
    }

    @Test
    void sourceGcFacadesTypecheckButActorKeywordRemainsReserved() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  actor.gc();
                  return;
                }

                pub routine main() => void {
                  process.gc();
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main() => void {
                  val actor = 1;
                  return;
                }
                """));
    }

    @Test
    void capabilityAdmissionRejectsProcessGcForStrictAndPrivateActorPolicies() {
        var processProgram = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  process.gc();
                  return;
                }
                """));
        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(processProgram, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(() ->
                CapabilityChecker.check(processProgram, IsolatePolicy.developer()));

        var privateActor = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  process.gc();
                  return;
                }
                """));
        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(privateActor, IsolatePolicy.developer()));

        var sharedActor = TypeChecker.check(Parser.parse("""
                pub shared actor fnc worker() => void {
                  process.gc();
                  return;
                }
                """));
        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(sharedActor, IsolatePolicy.developer()));
    }

    @Test
    void actorGcAuthorityIsMailboxScopedAndCannotBeExtracted() {
        var actorProgram = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  actor.gc();
                  return;
                }
                """));
        assertDoesNotThrow(() ->
                CapabilityChecker.check(actorProgram, IsolatePolicy.strictFaas()));

        var outsideActor = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  actor.gc();
                  return;
                }
                """));
        SecurityException outside = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(outsideActor, IsolatePolicy.developer()));
        assertTrue(outside.getMessage().contains("mailbox context"));

        var extracted = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  val collect = actor.gc;
                  return;
                }
                """));
        SecurityException extraction = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(extracted, IsolatePolicy.developer()));
        assertTrue(extraction.getMessage().contains("cannot be extracted"));

        var closure = TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => void {
                  val collect = () -> {
                    actor.gc();
                    return;
                  };
                  return;
                }
                """));
        SecurityException closureEscape = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(closure, IsolatePolicy.developer()));
        assertTrue(closureEscape.getMessage().contains("mailbox context"));
    }

    @Test
    void repeatedExplicitProcessGcIsCooldownLimited() {
        RecordingCollector collector = new RecordingCollector();
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(0, 0, TimeUnit.SECONDS.toNanos(30)),
                collector)) {
            runtime.gcProcess();
            runtime.gcProcess();
            assertEquals(2, runtime.gcStats().processRequests());
            assertEquals(1, runtime.gcStats().processCollections());
            assertEquals(1, runtime.gcStats().suppressedProcessRequests());
            assertEquals(List.of(ActorRuntime.GcReason.EXPLICIT), collector.processCollections);
        }
    }

    @Test
    void repeatedExplicitActorGcIsCoalescedPerMailboxMessage() throws Exception {
        RecordingCollector collector = new RecordingCollector();
        CountDownLatch firstMessage = new CountDownLatch(1);
        CountDownLatch secondMessage = new CountDownLatch(1);
        AtomicInteger seen = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(0, 0, 0),
                collector)) {
            var ref = runtime.<String>spawnPrivate(factory -> (message, context) -> {
                context.gc();
                context.gc();

                if (seen.incrementAndGet() == 1) {
                    firstMessage.countDown();
                } else {
                    secondMessage.countDown();
                    context.self().stop();
                }
            });

            ref.send("first");
            assertTrue(firstMessage.await(2, TimeUnit.SECONDS));
            ref.send("second");
            assertTrue(secondMessage.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());

            assertEquals(4, runtime.gcStats().actorRequests());
            assertEquals(2, runtime.gcStats().suppressedActorRequests());
            assertEquals(2, runtime.gcStats().actorCollections());
            assertEquals(2, collector.actorCollections.size());
        }
    }

    @Test
    void actorMailboxBoundariesRejectBorrowedStateAndBorrowedApis() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor BadState {
                  val &String borrowed;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor BadApi {
                  pub fnc leak(&String value) => &String {
                    return value;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc bad(&String input) => void {
                  return;
                }
                """)));
    }

    @Test
    void periodicCollectorNonFatalErrorDoesNotKillActor() throws Exception {
        ActorRuntime.GarbageCollector collector = new ActorRuntime.GarbageCollector() {
            @Override
            public void collectActor(
                    ActorRuntime.ActorId actorId,
                    ActorRuntime.ActorKind kind,
                    ActorRuntime.GcReason reason) {
                if (reason == ActorRuntime.GcReason.PERIODIC) {
                    throw new AssertionError("synthetic periodic collector failure");
                }
            }

            @Override
            public void collectProcess(ActorRuntime.GcReason reason) { }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(1, 0, 0),
                collector)) {
            var ref = runtime.<String>spawnPrivate(factory -> (message, context) -> {
                context.self().stop();
            });

            ref.send("collect");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void actorStartHookFailureRollsBackCollectorAndActorSlot() {
        AtomicInteger exitHooks = new AtomicInteger();
        ActorRuntime.GarbageCollector collector = new ActorRuntime.GarbageCollector() {
            @Override
            public void actorStarted(
                    ActorRuntime.ActorId actorId,
                    ActorRuntime.ActorKind kind,
                    IsolatePolicy policy) {
                throw new AssertionError("synthetic partial actorStarted failure");
            }

            @Override
            public void actorExited(ActorRuntime.ActorId actorId, ActorRuntime.ActorKind kind) {
                exitHooks.incrementAndGet();
            }

            @Override
            public void collectActor(
                    ActorRuntime.ActorId actorId,
                    ActorRuntime.ActorKind kind,
                    ActorRuntime.GcReason reason) { }

            @Override
            public void collectProcess(ActorRuntime.GcReason reason) { }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(0, 0, 0),
                collector)) {
            assertThrows(AssertionError.class,
                    () -> runtime.<String>spawnPrivate(factory -> (message, context) -> { }));
            assertEquals(1, exitHooks.get());
            assertEquals(0, runtime.actorCount());
            assertEquals(0, runtime.privateMemoryBytes());
        }
    }

    @Test
    void actorExitHookNonFatalErrorCannotStrandFinalization() throws Exception {
        AtomicInteger exitHooks = new AtomicInteger();
        ActorRuntime.GarbageCollector collector = new ActorRuntime.GarbageCollector() {
            @Override
            public void actorExited(ActorRuntime.ActorId actorId, ActorRuntime.ActorKind kind) {
                exitHooks.incrementAndGet();
                throw new AssertionError("synthetic actorExited failure");
            }

            @Override
            public void collectActor(
                    ActorRuntime.ActorId actorId,
                    ActorRuntime.ActorKind kind,
                    ActorRuntime.GcReason reason) { }

            @Override
            public void collectProcess(ActorRuntime.GcReason reason) { }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                ActorRuntime.TurnExecutor.direct(),
                new ActorRuntime.GcConfig(0, 0, 0),
                collector)) {
            var ref = runtime.<String>spawnPrivate(factory -> (message, context) -> {
                context.self().stop();
            });

            ref.send("stop");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(1, exitHooks.get());
            assertEquals(0, runtime.actorCount());
            assertEquals(0, runtime.privateMemoryBytes());
        }
    }

    private record ActorCollection(
            ActorRuntime.ActorId actorId,
            ActorRuntime.ActorKind kind,
            ActorRuntime.GcReason reason) { }

    private static final class RecordingCollector implements ActorRuntime.GarbageCollector {
        private final List<ActorCollection> actorCollections = new CopyOnWriteArrayList<>();
        private final List<ActorRuntime.GcReason> processCollections = new CopyOnWriteArrayList<>();

        @Override
        public void collectActor(
                ActorRuntime.ActorId actorId,
                ActorRuntime.ActorKind kind,
                ActorRuntime.GcReason reason) {
            actorCollections.add(new ActorCollection(actorId, kind, reason));
        }

        @Override
        public void collectProcess(ActorRuntime.GcReason reason) {
            processCollections.add(reason);
        }
    }
}
