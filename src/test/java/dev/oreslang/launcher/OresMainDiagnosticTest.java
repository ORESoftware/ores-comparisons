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
        assertEquals("", run.stdout());
        assertTrue(run.stderr().contains("Oreslang causal async stack:"), run.stderr());
        assertTrue(run.stderr().contains("explode"), run.stderr());
        assertTrue(run.stderr().contains("--- await"), run.stderr());
        assertFalse(run.stderr().contains("java.lang.Thread"), run.stderr());
    }

    @Test
    void asyncRuntimeFailureJsonCarriesAwaitEventsAndSourceLocations() throws Exception {
        Run run = invoke("async-runtime-error.ores", true);

        assertEquals(1, run.status());
        assertEquals("", run.stdout());
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
