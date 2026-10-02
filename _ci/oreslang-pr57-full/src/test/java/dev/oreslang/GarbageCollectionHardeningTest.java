package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
                new ActorRuntime.GcConfig(1, 0),
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
                new ActorRuntime.GcConfig(0, 0),
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
                new ActorRuntime.GcConfig(0, 0),
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
