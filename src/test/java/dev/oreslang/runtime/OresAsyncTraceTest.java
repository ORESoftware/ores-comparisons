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
        assertTrue(logical.logicalTrace().contains("--- await"));
        assertTrue(logical.getStackTrace().length >= 3);

        OresAsyncTrace.attach(failure, trace);
        assertEquals(1, failure.getSuppressed().length,
                "the same logical trace must not be attached twice");
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
}
