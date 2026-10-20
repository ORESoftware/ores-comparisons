package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresAsyncTraceTest {

    private static OresAsyncTrace.SourceSite site(int line) {
        return new OresAsyncTrace.SourceSite(
                "trace-test.ores",
                line,
                7,
                "19");
    }

    @Test
    void repeatedSelfTailAwaitIsRunLengthCompressed() {
        OresAsyncTrace.Frame walk =
                new OresAsyncTrace.Frame("walk", site(10));
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(walk);

        for (int i = 0; i < 100_000; i++) {
            trace.tailAwait(walk, site(14));
        }

        assertEquals(1, trace.retainedEventCount(),
                "tail recursion must not rebuild an O(n) diagnostic stack");
        assertEquals(0, trace.elidedEvents());
        assertTrue(trace.render().contains("repeated 100000 times"));
        assertTrue(trace.render().contains("@gen=19"));
        assertTrue(trace.render().contains("trace-test.ores:14:7"));
    }

    @Test
    void nonRepeatingHistoryIsBoundedAndReportsElision() {
        OresAsyncTrace.Frame root =
                new OresAsyncTrace.Frame("root", site(1));
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(root);

        for (int i = 0; i < 400; i++) {
            trace.awaitAt(new OresAsyncTrace.SourceSite(
                    "trace-test.ores",
                    i + 2,
                    3,
                    "19"));
        }

        assertTrue(trace.retainedEventCount() <= 96);
        assertTrue(trace.elidedEvents() > 0);
        assertTrue(trace.render().contains("older async trace events elided"));
    }

    @Test
    void logicalTraceAttachmentPreservesOriginalFailureAndStructuredFrames() {
        OresAsyncTrace.Frame caller =
                new OresAsyncTrace.Frame("caller", site(20));
        OresAsyncTrace.Frame callee =
                new OresAsyncTrace.Frame("callee", site(30));
        OresAsyncTrace.Trace trace =
                OresAsyncTrace.root(caller).child(callee, site(25));
        trace.awaitAt(site(31));

        IllegalStateException failure = new IllegalStateException("boom");
        assertSame(failure, OresAsyncTrace.attach(failure, trace));
        assertEquals(1, failure.getSuppressed().length);

        OresAsyncTrace.LogicalAsyncStackTrace logical =
                assertInstanceOf(
                        OresAsyncTrace.LogicalAsyncStackTrace.class,
                        failure.getSuppressed()[0]);
        assertTrue(logical.logicalTrace().contains("callee"));
        assertTrue(logical.logicalTrace().contains("caller"));
        assertTrue(logical.logicalTrace().contains("trace-test.ores:25:7"),
                logical.logicalTrace());
        assertFalse(logical.logicalTrace().contains("caller (trace-test.ores:20:7"),
                logical.logicalTrace());
        assertTrue(logical.logicalTrace().contains("--- await"));
        assertTrue(logical.getStackTrace().length >= 3);
        assertEquals("caller", logical.getStackTrace()[2].getMethodName());
        assertEquals(25, logical.getStackTrace()[2].getLineNumber());

        OresAsyncTrace.attach(failure, trace);
        assertEquals(1, failure.getSuppressed().length,
                "the same logical trace must not be attached twice");
    }

    @Test
    void failureKeepsChildAndParentLogicalTracesAcrossSchedulerAwait() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresAsyncTrace.Trace childTrace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame("child_async", site(60)));
            OresAsyncTrace.Trace parentTrace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame("parent_async", site(70)));
            OresFuture<Integer> child = new OresFuture<>();

            OresFuture<Integer> parent = scheduler.start(new OresScheduler.Task<>() {
                private int pc;

                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(parentTrace)) {
                        if (pc++ == 0) {
                            parentTrace.awaitAt(site(71));
                            return OresScheduler.await(child);
                        }

                        assertNotNull(resume.failure());
                        Throwable failure = OresFuture.unwrap(resume.failure());
                        OresAsyncTrace.attach(failure, parentTrace);
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        if (failure instanceof Error error) throw error;
                        throw new RuntimeException(failure);
                    }
                }
            });

            OresFuture<Void> producer = scheduler.startSync(() -> {
                try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(childTrace)) {
                    childTrace.awaitAt(site(61));
                    IllegalStateException failure =
                            new IllegalStateException("async child failed");
                    OresAsyncTrace.attach(failure, childTrace);
                    child.failFromRuntime(failure);
                    return null;
                }
            });

            producer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.ExecutionException thrown = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> parent.get(5, java.util.concurrent.TimeUnit.SECONDS));

            Throwable failure = thrown.getCause();
            assertInstanceOf(IllegalStateException.class, failure);

            java.util.List<OresAsyncTrace.LogicalAsyncStackTrace> logical =
                    java.util.Arrays.stream(failure.getSuppressed())
                            .filter(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance)
                            .map(OresAsyncTrace.LogicalAsyncStackTrace.class::cast)
                            .toList();

            assertEquals(2, logical.size(),
                    "child failure and awaiting parent must retain distinct causal traces");
            assertTrue(logical.stream()
                    .anyMatch(trace -> trace.logicalTrace().contains("child_async")));
            assertTrue(logical.stream()
                    .anyMatch(trace -> trace.logicalTrace().contains("parent_async")
                            && trace.logicalTrace().contains("--- await")));
        }
    }

    @Test
    void actorBoundaryIsRetainedAsLogicalCausalMetadata() {
        OresAsyncTrace.Frame worker =
                new OresAsyncTrace.Frame("Worker.receive_message", site(50));
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(worker);
        trace.boundary(OresAsyncTrace.BoundaryKind.ACTOR_MESSAGE, site(49));

        assertTrue(trace.render().contains("actor-message"));
        assertTrue(trace.render().contains("Worker.receive_message"));
    }
    @Test
    void deepestSynchronousGuestStackIsAttachedExactlyOnce() {
        OresAsyncTrace.Trace caller = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("caller", site(75)));
        OresAsyncTrace.Trace leaf = caller.child(
                new OresAsyncTrace.Frame("leaf", site(76)),
                site(76));
        IllegalStateException failure = new IllegalStateException("sync");

        OresAsyncTrace.attachGuest(failure, leaf);
        OresAsyncTrace.attachGuest(failure, caller);

        java.util.List<OresAsyncTrace.LogicalGuestStackTrace> guest =
                java.util.Arrays.stream(failure.getSuppressed())
                        .filter(OresAsyncTrace.LogicalGuestStackTrace.class::isInstance)
                        .map(OresAsyncTrace.LogicalGuestStackTrace.class::cast)
                        .toList();
        assertEquals(1, guest.size());
        assertTrue(guest.getFirst().logicalTrace().contains("leaf"));
        assertTrue(guest.getFirst().logicalTrace().contains("caller"));
    }

    @Test
    void evolvedTraceGetsANewerSnapshotButExactRevisionDoesNotDuplicate() {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("worker", site(80)));
        IllegalStateException failure = new IllegalStateException("boom");

        OresAsyncTrace.attach(failure, trace);
        OresAsyncTrace.attach(failure, trace);
        assertEquals(1, failure.getSuppressed().length);

        trace.awaitAt(site(81));
        OresAsyncTrace.attach(failure, trace);
        OresAsyncTrace.attach(failure, trace);

        java.util.List<OresAsyncTrace.LogicalAsyncStackTrace> logical =
                java.util.Arrays.stream(failure.getSuppressed())
                        .filter(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance)
                        .map(OresAsyncTrace.LogicalAsyncStackTrace.class::cast)
                        .toList();
        assertEquals(2, logical.size(),
                "a later revision of the same logical task must retain newer causal history");
        assertFalse(logical.get(0).logicalTrace().contains("--- await"));
        assertTrue(logical.get(1).logicalTrace().contains("--- await"));
    }

    @Test
    void sourceSiteRedactsUriCredentialsQueryAndFragmentEverywhere() {
        OresAsyncTrace.SourceSite hostile = new OresAsyncTrace.SourceSite(
                "https://alice:super-secret@example.test/pkg/main.ores?token=abc123#private",
                12,
                7,
                "g1");
        assertEquals("https://example.test/pkg/main.ores", hostile.sourceId());

        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("worker", hostile));
        IllegalStateException failure = new IllegalStateException("boom");
        OresAsyncTrace.attach(failure, trace);
        OresAsyncTrace.LogicalAsyncStackTrace logical =
                assertInstanceOf(
                        OresAsyncTrace.LogicalAsyncStackTrace.class,
                        failure.getSuppressed()[0]);

        assertTrue(logical.logicalTrace().contains("https://example.test/pkg/main.ores"));
        assertFalse(logical.logicalTrace().contains("alice"));
        assertFalse(logical.logicalTrace().contains("super-secret"));
        assertFalse(logical.logicalTrace().contains("abc123"));
        assertFalse(logical.logicalTrace().contains("private"));
        assertEquals(
                "https://example.test/pkg/main.ores",
                logical.getStackTrace()[0].getFileName());

        String json = OresDiagnosticRenderer.renderJson(failure);
        assertTrue(json.contains("https://example.test/pkg/main.ores"), json);
        assertFalse(json.contains("super-secret"), json);
        assertFalse(json.contains("abc123"), json);
    }

    @Test
    void syntheticJavaTraceEscapesLogSplittingAndBidiMetadata() {
        OresAsyncTrace.SourceSite hostileSite = new OresAsyncTrace.SourceSite(
                "evil.ores\r\nFORGED\u2028line",
                9,
                4,
                "gen\u202eoverride");
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("worker\nFORGED\u202e", hostileSite));
        trace.awaitAt(hostileSite);

        IllegalStateException failure = new IllegalStateException("boom");
        OresAsyncTrace.attach(failure, trace);
        OresAsyncTrace.LogicalAsyncStackTrace logical =
                assertInstanceOf(
                        OresAsyncTrace.LogicalAsyncStackTrace.class,
                        failure.getSuppressed()[0]);

        assertTrue(logical.logicalTrace().contains("worker\\nFORGED\\u202e"));
        assertTrue(logical.logicalTrace().contains("evil.ores\\r\\nFORGED\\u2028line"));
        assertFalse(logical.logicalTrace().contains("worker\nFORGED"));
        for (StackTraceElement frame : logical.getStackTrace()) {
            assertFalse(frame.getMethodName().contains("\n"));
            assertFalse(frame.getFileName() != null && frame.getFileName().contains("\n"));
            assertFalse(frame.getClassName().contains("\u202e"));
        }
    }

    @Test
    void sameTraceNestedScopesStillRequireStrictLifoClose() {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("same", site(88)));

        OresAsyncTrace.Scope outer = OresAsyncTrace.install(trace);
        OresAsyncTrace.Scope inner = OresAsyncTrace.install(trace);
        try {
            assertThrows(IllegalStateException.class, outer::close);
            assertSame(trace, OresAsyncTrace.current());

            inner.close();
            assertSame(trace, OresAsyncTrace.current());

            outer.close();
            assertNull(OresAsyncTrace.current());
        } finally {
            try {
                inner.close();
            } finally {
                outer.close();
            }
        }
    }

    @Test
    void traceLeaseRejectsConcurrentCarrierButAllowsSequentialMigration() throws Exception {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("migrating", site(89)));
        java.util.concurrent.atomic.AtomicReference<Throwable> concurrentFailure =
                new java.util.concurrent.atomic.AtomicReference<>();

        OresAsyncTrace.Scope first = OresAsyncTrace.install(trace);
        try {
            Thread competing = new Thread(() -> {
                try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(trace)) {
                    concurrentFailure.set(
                            new AssertionError("concurrent trace install unexpectedly succeeded"));
                } catch (Throwable failure) {
                    concurrentFailure.set(failure);
                }
            });
            competing.start();
            competing.join();

            assertInstanceOf(IllegalStateException.class, concurrentFailure.get());
            assertSame(trace, OresAsyncTrace.current());
        } finally {
            first.close();
        }
        assertNull(OresAsyncTrace.current());

        java.util.concurrent.atomic.AtomicReference<Throwable> migratedFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread migrated = new Thread(() -> {
            try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(trace)) {
                assertSame(trace, OresAsyncTrace.current());
                trace.awaitAt(site(90));
            } catch (Throwable failure) {
                migratedFailure.set(failure);
            }
        });
        migrated.start();
        migrated.join();

        assertNull(migratedFailure.get(),
                () -> "sequential carrier migration failed: " + migratedFailure.get());
        IllegalStateException failure = new IllegalStateException("after-migration");
        OresAsyncTrace.attach(failure, trace);
        OresAsyncTrace.LogicalAsyncStackTrace logical =
                assertInstanceOf(
                        OresAsyncTrace.LogicalAsyncStackTrace.class,
                        failure.getSuppressed()[0]);
        assertTrue(logical.logicalTrace().contains("--- await"));
    }

    @Test
    void traceScopesRejectCrossThreadAndOutOfOrderCloseWithoutLeakingCurrentTrace() throws Exception {
        OresAsyncTrace.Trace outer = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("outer", site(90)));
        OresAsyncTrace.Trace inner = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("inner", site(91)));

        OresAsyncTrace.Scope outerScope = OresAsyncTrace.install(outer);
        OresAsyncTrace.Scope innerScope = OresAsyncTrace.install(inner);
        assertSame(inner, OresAsyncTrace.current());

        assertThrows(IllegalStateException.class, outerScope::close);
        assertSame(inner, OresAsyncTrace.current(),
                "failed out-of-order close must not corrupt the installed trace");

        java.util.concurrent.atomic.AtomicReference<Throwable> crossThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread wrongCarrier = new Thread(() -> {
            try {
                innerScope.close();
            } catch (Throwable failure) {
                crossThread.set(failure);
            }
        });
        wrongCarrier.start();
        wrongCarrier.join();
        assertInstanceOf(IllegalStateException.class, crossThread.get());
        assertSame(inner, OresAsyncTrace.current());

        innerScope.close();
        assertSame(outer, OresAsyncTrace.current());
        outerScope.close();
        assertNull(OresAsyncTrace.current());
    }

    @Test
    void attachedLogicalCausalTracesAreBoundedPerFailure() {
        IllegalStateException failure = new IllegalStateException("fanout");
        for (int i = 0; i < 100; i++) {
            OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame("waiter_" + i, site(100 + i)));
            OresAsyncTrace.attach(failure, trace);
        }

        long logical = java.util.Arrays.stream(failure.getSuppressed())
                .filter(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance)
                .count();
        java.util.List<OresAsyncTrace.LogicalAsyncTraceElision> markers =
                java.util.Arrays.stream(failure.getSuppressed())
                        .filter(OresAsyncTrace.LogicalAsyncTraceElision.class::isInstance)
                        .map(OresAsyncTrace.LogicalAsyncTraceElision.class::cast)
                        .toList();

        assertEquals(32, logical);
        assertEquals(1, markers.size());
        assertEquals(68, markers.getFirst().elidedTraces());
        assertEquals(33, failure.getSuppressed().length);
    }


}
