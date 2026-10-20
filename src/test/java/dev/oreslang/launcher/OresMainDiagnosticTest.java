package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class OresMainDiagnosticTest {
    @Test
    void parseFailureGoesToStderrAndLeavesStdoutClean() throws Exception {
        Run run = invoke("parse-error.ores", false);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        assertFalse(run.stderr().isBlank(), run.stderr());
        assertTrue(
                run.stderr().toLowerCase(java.util.Locale.ROOT).contains("parse")
                        || run.stderr().toLowerCase(java.util.Locale.ROOT).contains("lexer"),
                run.stderr());
    }

    @Test
    void jsonFlagMakesParseFailureOneStructuredStderrRecord() throws Exception {
        Run run = invoke("parse-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.startsWith("{\"schema\":\"oreslang.error.v1\""), json);
        assertTrue(json.contains("\"error\":{"), json);
        assertTrue(json.contains("\"message\":"), json);
        assertTrue(json.endsWith("}"), json);
    }

    @Test
    void asyncTypeFailureIsAlsoStructuredAndNeverWrittenToStdout() throws Exception {
        Run run = invoke("type-error-await.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        assertTrue(run.stderr().contains("\"schema\":\"oreslang.error.v1\""), run.stderr());
        assertTrue(run.stderr().contains("Awaitable")
                        || run.stderr().contains("await"),
                run.stderr());
    }

    @Test
    void asyncRuntimeFailurePrintsLogicalAwaitTraceInTextMode() throws Exception {
        Run run = invoke("async-runtime-error.ores", false);

        assertEquals(1, run.status());
        assertEquals("before-error\n", run.stdout());
        assertTrue(run.stderr().contains("Oreslang causal async stack:"), run.stderr());
        assertTrue(run.stderr().contains("explode"), run.stderr());
        assertTrue(run.stderr().contains("--- await"), run.stderr());
        assertFalse(run.stderr().contains("java.lang.Thread"), run.stderr());
    }

    @Test
    void asyncRuntimeFailureJsonCarriesAwaitEventsAndSourceLocations() throws Exception {
        Run run = invoke("async-runtime-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("before-error\n", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.startsWith("{\"schema\":\"oreslang.error.v1\""), json);
        assertTrue(json.contains("\"async_traces\":["), json);
        assertTrue(json.contains("\"kind\":\"await\""), json);
        assertTrue(json.contains("async-runtime-error.ores"), json);
        assertTrue(json.contains("\"line\":"), json);
        assertTrue(json.contains("\"column\":"), json);
        assertFalse(json.contains("OresScheduler$TaskRunner"), json);
    }

    @Test
    void synchronousPanicRetainsGuestStackAndBypassesOrdinaryJavaFrames() throws Exception {
        Run run = invoke("sync-panic-stack-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.contains("Option::unwrap"), json);
        assertTrue(json.contains("\"guest_stack\":{"), json);
        assertTrue(json.contains("\"symbol\":\"panic_leaf\""), json);
        assertTrue(json.contains("\"symbol\":\"panic_middle\""), json);
        assertTrue(json.contains("\"symbol\":\"main\""), json);
        assertFalse(json.contains("java.lang.Thread"), json);
    }

    @Test
    void asyncPanicAfterAwaitRetainsCausalTrace() throws Exception {
        Run run = invoke("async-panic-stack-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.contains("Option::unwrap"), json);
        assertTrue(json.contains("\"async_traces\":[{"), json);
        assertTrue(json.contains("\"symbol\":\"panic_after_await\""), json);
        assertTrue(json.contains("\"kind\":\"await\""), json);
        assertTrue(json.contains("diagnostics/async-panic-stack-error.ores"), json);
    }

    @Test
    void synchronousFailurePrintsDeepestGuestCallStack() throws Exception {
        Run run = invoke("sync-stack-chain-error.ores", false);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        assertTrue(run.stderr().contains("Oreslang logical stack:"), run.stderr());
        assertTrue(run.stderr().contains("leaf"), run.stderr());
        assertTrue(run.stderr().contains("middle"), run.stderr());
        assertTrue(run.stderr().contains("main"), run.stderr());
        assertTrue(run.stderr().contains(
                "middle (diagnostics/sync-stack-chain-error.ores:7:14"),
                run.stderr());
        assertTrue(run.stderr().contains(
                "main (diagnostics/sync-stack-chain-error.ores:11:21"),
                run.stderr());
        assertTrue(run.stderr().contains("diagnostics/sync-stack-chain-error.ores"), run.stderr());
        assertFalse(run.stderr().contains("java.lang.Thread"), run.stderr());
    }

    @Test
    void synchronousFailureJsonSeparatesGuestStackFromAsyncCausality() throws Exception {
        Run run = invoke("sync-stack-chain-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.contains("\"guest_stack\":{"), json);
        assertTrue(json.contains("\"symbol\":\"leaf\""), json);
        assertTrue(json.contains("\"symbol\":\"middle\""), json);
        assertTrue(json.contains("\"symbol\":\"main\""), json);
        assertTrue(json.contains(
                "\"site\":{\"source\":\"diagnostics/sync-stack-chain-error.ores\",\"line\":7,\"column\":14"),
                json);
        assertTrue(json.contains(
                "\"site\":{\"source\":\"diagnostics/sync-stack-chain-error.ores\",\"line\":11,\"column\":21"),
                json);
        assertTrue(json.contains("\"async_traces\":[]"), json);
        assertFalse(json.contains("target/test-classes"), json);
    }

    @Test
    void synchronousLambdaFailureUsesLambdaDeclarationAndInvocationSites() throws Exception {
        Run run = invoke("sync-lambda-stack-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.contains("Option::unwrap"), json);
        assertTrue(json.contains("\"symbol\":\"<lambda>\""), json);
        assertTrue(json.contains(
                "\"current\":{\"symbol\":\"<lambda>\",\"site\":{\"source\":\"diagnostics/sync-lambda-stack-error.ores\",\"line\":2,\"column\":14"),
                json);
        assertTrue(json.contains(
                "\"site\":{\"source\":\"diagnostics/sync-lambda-stack-error.ores\",\"line\":6,\"column\":7"),
                json);
        assertTrue(json.contains("\"symbol\":\"main\""), json);
    }

    @Test
    void multiAwaitFailureRetainsLeafMiddleTopCausalFramesInTextMode() throws Exception {
        Run run = invoke("async-stack-chain-error.ores", false);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        assertTrue(run.stderr().contains("Oreslang causal async stack:"), run.stderr());
        assertTrue(run.stderr().contains("leaf"), run.stderr());
        assertTrue(run.stderr().contains("middle"), run.stderr());
        assertTrue(run.stderr().contains("top"), run.stderr());
        assertTrue(run.stderr().contains("--- await"), run.stderr());
        assertFalse(run.stderr().contains("OresScheduler$TaskRunner"), run.stderr());
        assertFalse(run.stderr().contains("java.lang.Thread"), run.stderr());
    }

    @Test
    void multiAwaitFailureJsonKeepsStructuredCausalFramesAndDeclaredOrder() throws Exception {
        Run run = invoke("async-stack-chain-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.startsWith("{\"schema\":\"oreslang.error.v1\""), json);
        assertTrue(json.contains("\"symbol\":\"leaf\""), json);
        assertTrue(json.contains("\"symbol\":\"middle\""), json);
        assertTrue(json.contains("\"symbol\":\"top\""), json);
        assertTrue(json.contains("\"kind\":\"await\""), json);
        assertTrue(json.contains("\"event_order\":\"oldest_to_newest\""), json);
        assertTrue(json.contains("diagnostics/async-stack-chain-error.ores"), json);
        assertFalse(json.contains("target/test-classes"), json);
        assertFalse(json.contains("/home/runner/"), json);
        assertFalse(json.contains("OresScheduler$TaskRunner"), json);
    }

    @Test
    void cancelledAwaitRecordsCancellationBoundary() throws Exception {
        Run run = invoke("async-cancel-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.contains("\"kind\":\"cancellation\""), json);
        assertTrue(json.contains("async-cancel-error.ores"), json);
        assertTrue(json.contains("\"kind\":\"await\""), json);
    }

    @Test
    void actorFailureRecordsSpawnAndMessageBoundaries() throws Exception {
        Run run = invoke("actor-stack-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
        String json = run.stderr().trim();
        assertTrue(json.contains("\"symbol\":\"explode_actor\""), json);
        assertTrue(json.contains("\"kind\":\"task_spawn\""), json);
        assertTrue(json.contains("\"kind\":\"actor_message\""), json);
        assertTrue(json.contains("actor-stack-error.ores"), json);
        assertFalse(json.contains("ActorRuntime$"), json);
    }

    @Test
    void eightActorFailureRetainsAtLeastLastFiveActorsInCliTextAndJson() throws Exception {
        Run structured = invoke("actor-stack-chain-error.ores", true);
        assertEquals(1, structured.status(), structured.stderr());
        assertEquals("", structured.stdout());
        String json = structured.stderr().trim();
        assertTrue(json.contains("\"actor_history_order\":\"oldest_to_newest\""), json);
        int start = json.indexOf("\"actor_history\":[");
        int end = json.indexOf(",\"elided_actor_visits\"", start);
        assertTrue(start >= 0 && end > start, json);
        String actorHistory = json.substring(start, end);
        int prior = -1;
        for (int actor = 4; actor <= 8; actor++) {
            int next = actorHistory.indexOf("\"symbol\":\"actor_" + actor + "\"");
            assertTrue(next > prior, "missing/out-of-order actor_" + actor + ": " + actorHistory);
            prior = next;
        }
        assertFalse(json.contains("ActorRuntime$"), json);

        Run human = invoke("actor-stack-chain-error.ores", false);
        assertEquals(1, human.status(), human.stderr());
        assertEquals("", human.stdout());
        assertTrue(human.stderr().contains("Actor causality (newest first):"),
                human.stderr());
        for (int actor = 4; actor <= 8; actor++) {
            assertTrue(human.stderr().contains("actor_" + actor), human.stderr());
        }
    }

    @Test
    void usageFailureUsesExitTwoAndJsonWhenRequested() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int status;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            status = OresMain.exitStatusForArgs(
                    new String[]{"--json", "--definitely-not-an-option"}, out, err);
        }

        assertEquals(2, status);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        String json = stderr.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("\"name\":\"UsageException\""), json);
        assertTrue(json.contains("unknown option"), json);
    }

    private static Run invoke(String fixture, boolean json) throws Exception {
        Path path = fixture(fixture);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        String[] args = json
                ? new String[]{"--json", path.toString()}
                : new String[]{path.toString()};

        int status;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            status = OresMain.exitStatusForArgs(args, out, err);
        }

        return new Run(
                status,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Path fixture(String name) throws URISyntaxException {
        var url = OresMainDiagnosticTest.class.getResource("/diagnostics/" + name);
        assertNotNull(url, "missing diagnostic fixture: " + name);
        return Path.of(url.toURI());
    }

    private record Run(int status, String stdout, String stderr) { }
}
