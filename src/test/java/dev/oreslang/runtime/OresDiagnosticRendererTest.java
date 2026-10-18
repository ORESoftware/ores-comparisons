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
        assertTrue(json.contains("\"elided_async_traces\":68"), json);
        assertTrue(text.contains("68 additional Oreslang causal trace(s) elided"), text);
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
