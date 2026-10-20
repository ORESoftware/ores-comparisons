package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresDiagnosticRendererTest {
    private static OresAsyncTrace.SourceSite site(String file, int line, int column) {
        return new OresAsyncTrace.SourceSite(file, line, column, "g17");
    }

    @Test
    void jsonCarriesStructuredAsyncBoundariesWithoutHostCarrierFrames() {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("child", site("child.ores", 8, 5)));
        trace.awaitAt(site("child.ores", 9, 12));
        trace.boundary(OresAsyncTrace.BoundaryKind.ACTOR_MESSAGE,
                site("actor.ores", 22, 3));
        trace.boundary(OresAsyncTrace.BoundaryKind.SELECT,
                site("select.ores", 31, 7));

        IllegalStateException failure = new IllegalStateException("boom");
        OresAsyncTrace.attach(failure, trace);

        String json = OresDiagnosticRenderer.renderJson(failure);
        assertTrue(json.startsWith("{\"schema\":\"oreslang.error.v1\""));
        assertTrue(json.contains("\"type\":\"java.lang.IllegalStateException\""), json);
        assertTrue(json.contains("\"message\":\"boom\""), json);
        assertTrue(json.contains("\"kind\":\"await\""), json);
        assertTrue(json.contains("\"kind\":\"actor_message\""), json);
        assertTrue(json.contains("\"kind\":\"select\""), json);
        assertTrue(json.contains("\"source\":\"child.ores\""), json);
        assertTrue(json.contains("\"line\":9"), json);
        assertTrue(json.contains("\"column\":12"), json);
        assertFalse(json.contains("java.lang.Thread"), json);
        assertFalse(json.contains("OresScheduler$"), json);
    }

    @Test
    void humanDiagnosticEscapesControlAndBidiSpoofingButKeepsAsyncSites() {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("worker", site("worker.ores", 4, 2)));
        trace.awaitAt(site("worker.ores", 5, 10));

        RuntimeException failure =
                new RuntimeException("line1\nline2\r\u202eSpoof");
        OresAsyncTrace.attach(failure, trace);

        String text = OresDiagnosticRenderer.renderText(failure);
        assertTrue(text.contains("line1\\nline2\\r\\u202eSpoof"), text);
        assertTrue(text.contains("Oreslang causal async stack:"), text);
        assertTrue(text.contains("--- await"), text);
        assertTrue(text.contains("worker.ores:5:10"), text);
        assertFalse(text.contains("\nline2\r"), text);
    }

    @Test
    void jsonEscapesQuotesNewlinesAndBidiControls() {
        IllegalArgumentException failure =
                new IllegalArgumentException("bad \"quote\"\n\u202e");
        String json = OresDiagnosticRenderer.renderJson(failure);

        assertTrue(json.contains("bad \\\"quote\\\"\\n\\u202e"), json);
        assertFalse(json.contains("\n\u202e"), json);
    }

    @Test
    void realSuppressedFailuresAreRenderedSeparatelyFromAsyncTraceMetadata() {
        IllegalStateException primary = new IllegalStateException("primary");
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("cleanup", site("cleanup.ores", 4, 1)));
        OresAsyncTrace.attach(primary, trace);
        primary.addSuppressed(new IllegalArgumentException("cleanup failed"));

        String json = OresDiagnosticRenderer.renderJson(primary);
        String text = OresDiagnosticRenderer.renderText(primary);

        assertTrue(json.contains("\"suppressed\":[{\"type\":\"java.lang.IllegalArgumentException\""), json);
        assertTrue(json.contains("\"message\":\"cleanup failed\""), json);
        assertTrue(json.contains("\"elided_suppressed\":0"), json);
        assertTrue(text.contains("Suppressed: java.lang.IllegalArgumentException: cleanup failed"), text);
    }

    @Test
    void suppressedFailureFanoutIsBounded() {
        IllegalStateException primary = new IllegalStateException("primary");
        for (int i = 0; i < 40; i++) {
            primary.addSuppressed(new IllegalArgumentException("s" + i));
        }

        String json = OresDiagnosticRenderer.renderJson(primary);
        String text = OresDiagnosticRenderer.renderText(primary);

        assertTrue(json.contains("\"elided_suppressed\":24"), json);
        assertTrue(text.contains("24 additional suppressed error(s) elided"), text);
        assertFalse(json.contains("\"message\":\"s39\""), json);
    }

    @Test
    void causeCyclesAreExplicitlyElidedInsteadOfRecursingForever() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        String json = OresDiagnosticRenderer.renderJson(first);
        String text = OresDiagnosticRenderer.renderText(first);
        assertTrue(json.contains("\"reason\":\"cause_cycle\""), json);
        assertTrue(text.contains("<cause cycle elided>"), text);
    }

    @Test
    void diagnosticSnapshotDoesNotChangeWhenTaskTraceMutatesLater() {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("worker", site("snapshot.ores", 10, 1)));
        trace.awaitAt(site("snapshot.ores", 11, 2));

        IllegalStateException failure = new IllegalStateException("frozen");
        OresAsyncTrace.attach(failure, trace);

        String before = OresDiagnosticRenderer.renderJson(failure);
        trace.boundary(OresAsyncTrace.BoundaryKind.RPC,
                site("later.ores", 99, 9));
        String after = OresDiagnosticRenderer.renderJson(failure);

        assertEquals(before, after,
                "attached diagnostics must be immutable failure-time evidence");
        assertFalse(after.contains("later.ores"), after);
        assertFalse(after.contains("\"kind\":\"rpc\""), after);
    }

    @Test
    void guestStackAndAsyncCausalityRenderAsSeparateLayers() {
        IllegalStateException failure = new IllegalStateException("mixed");
        OresAsyncTrace.Trace sync = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("sync_leaf", site("mixed.ores", 4, 2)));
        OresAsyncTrace.Trace async = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("async_parent", site("mixed.ores", 10, 2)));
        async.awaitAt(site("mixed.ores", 11, 4));

        OresAsyncTrace.attachGuest(failure, sync);
        OresAsyncTrace.attach(failure, async);

        String json = OresDiagnosticRenderer.renderJson(failure);
        String text = OresDiagnosticRenderer.renderText(failure);

        assertTrue(json.contains("\"guest_stack\":{"), json);
        assertTrue(json.contains("\"symbol\":\"sync_leaf\""), json);
        assertTrue(json.contains("\"async_traces\":[{"), json);
        assertTrue(json.contains("\"symbol\":\"async_parent\""), json);
        assertTrue(text.contains("Oreslang logical stack:"), text);
        assertTrue(text.contains("Oreslang causal async stack:"), text);
        assertFalse(text.contains("Suppressed: dev.oreslang.runtime.OresAsyncTrace"), text);
    }

    @Test
    void sharedThrowableDagIsNotMisreportedAsACycle() {
        IllegalArgumentException shared = new IllegalArgumentException("shared-leaf");
        RuntimeException left = new RuntimeException("left", shared);
        RuntimeException right = new RuntimeException("right", shared);
        IllegalStateException root = new IllegalStateException("root");
        root.addSuppressed(left);
        root.addSuppressed(right);

        String json = OresDiagnosticRenderer.renderJson(root);
        String text = OresDiagnosticRenderer.renderText(root);

        assertFalse(json.contains("\"reason\":\"cause_cycle\""), json);
        assertFalse(text.contains("<cause cycle elided>"), text);
        assertEquals(2, json.split("shared-leaf", -1).length - 1,
                "a shared DAG leaf should render on each causal path");
    }

    @Test
    void pathologicalErrorGraphIsGloballyBounded() {
        IllegalStateException root = new IllegalStateException("root");
        for (int i = 0; i < 16; i++) {
            RuntimeException branch = new RuntimeException("branch-" + i);
            for (int j = 0; j < 16; j++) {
                branch.addSuppressed(new IllegalArgumentException("leaf-" + i + "-" + j));
            }
            root.addSuppressed(branch);
        }

        String json = OresDiagnosticRenderer.renderJson(root);
        String text = OresDiagnosticRenderer.renderText(root);

        assertTrue(json.contains("\"reason\":\"error_budget\""), json);
        assertTrue(text.contains("<error graph truncated>"), text);
        assertTrue(json.length() < 200_000, "JSON error graph must remain globally bounded");
        assertTrue(text.length() < 100_000, "text error graph must remain globally bounded");
    }

    @Test
    void unicodeLineSeparatorsCannotSplitTextOrJsonDiagnostics() {
        IllegalStateException failure =
                new IllegalStateException("first\u2028second\u2029third");

        String json = OresDiagnosticRenderer.renderJson(failure);
        String text = OresDiagnosticRenderer.renderText(failure);

        assertTrue(json.contains("first\\u2028second\\u2029third"), json);
        assertTrue(text.contains("first\\u2028second\\u2029third"), text);
        assertFalse(json.contains("first\u2028second"), json);
        assertFalse(text.contains("first\u2028second"), text);
    }

    @Test
    void jsonDeclaresChronologicalEventOrderWhileTextShowsCausalReverseOrder() {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("worker", site("order.ores", 1, 1)));
        trace.awaitAt(site("order.ores", 2, 1));
        trace.boundary(OresAsyncTrace.BoundaryKind.SELECT, site("order.ores", 3, 1));
        IllegalStateException failure = new IllegalStateException("order");
        OresAsyncTrace.attach(failure, trace);

        String json = OresDiagnosticRenderer.renderJson(failure);
        String text = OresDiagnosticRenderer.renderText(failure);

        assertTrue(json.contains("\"event_order\":\"oldest_to_newest\""), json);
        assertTrue(json.indexOf("\"kind\":\"await\"")
                < json.indexOf("\"kind\":\"select\""), json);
        assertTrue(text.indexOf("--- select") < text.indexOf("--- await"), text);
    }

    @Test
    void attachedTraceFanoutRemainsBoundedInJsonAndText() {
        IllegalStateException failure = new IllegalStateException("fanout");
        for (int i = 0; i < 100; i++) {
            OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame(
                            "waiter_" + i,
                            site("fanout.ores", i + 1, 1)));
            OresAsyncTrace.attach(failure, trace);
        }

        String json = OresDiagnosticRenderer.renderJson(failure);
        String text = OresDiagnosticRenderer.renderText(failure);
        assertTrue(json.contains("\"elided_async_traces\":92"), json);
        assertTrue(text.contains("92 additional Oreslang causal trace(s) elided"), text);
    }

    @Test
    void rendererKeepsNewestTraceEventsAndAccountsForDisplayElision() {
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                new OresAsyncTrace.Frame("worker", site("events.ores", 1, 1)));
        for (int i = 0; i < 80; i++) {
            trace.awaitAt(site("events.ores", i + 2, 1));
        }
        IllegalStateException failure = new IllegalStateException("many-events");
        OresAsyncTrace.attach(failure, trace);

        String json = OresDiagnosticRenderer.renderJson(failure);
        String text = OresDiagnosticRenderer.renderText(failure);

        assertTrue(json.contains("\"elided_events\":16"), json);
        assertTrue(text.contains("16 older async trace events elided"), text);
        assertFalse(json.contains("\"line\":2,"), json);
        assertTrue(json.contains("\"line\":81,"), json);
    }

    @Test
    void asyncTraceRenderBudgetIsGlobalAcrossErrorGraph() {
        IllegalStateException root = new IllegalStateException("root");
        for (int i = 0; i < 20; i++) {
            RuntimeException child = new RuntimeException("child-" + i);
            OresAsyncTrace.Trace trace = OresAsyncTrace.root(
                    new OresAsyncTrace.Frame("worker_" + i, site("budget.ores", i + 1, 1)));
            trace.awaitAt(site("budget.ores", i + 2, 1));
            OresAsyncTrace.attach(child, trace);
            root.addSuppressed(child);
        }

        String json = OresDiagnosticRenderer.renderJson(root);
        int rendered = json.split("\"event_order\":\"oldest_to_newest\"", -1).length - 1;

        assertEquals(16, rendered, "global render budget must cap logical trace expansion");
        assertTrue(json.contains("\"elided_async_traces\":1"), json);
        assertTrue(json.length() < 200_000, "global async trace budget must bound output");
    }

    @Test
    void tailAwaitCompressionSurvivesJsonSerialization() {
        OresAsyncTrace.Frame walk =
                new OresAsyncTrace.Frame("walk", site("walk.ores", 2, 1));
        OresAsyncTrace.Trace trace = OresAsyncTrace.root(walk);
        for (int i = 0; i < 100_000; i++) {
            trace.tailAwait(walk, site("walk.ores", 6, 10));
        }
        IllegalStateException failure = new IllegalStateException("deep");
        OresAsyncTrace.attach(failure, trace);

        String json = OresDiagnosticRenderer.renderJson(failure);
        assertTrue(json.contains("\"kind\":\"tail_await\""), json);
        assertTrue(json.contains("\"repetitions\":100000"), json);
        assertTrue(json.length() < 20_000,
                "bounded trace serialization must not recreate recursion depth");
    }
}
