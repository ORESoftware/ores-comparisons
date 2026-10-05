package dev.oreslang.runtime;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedProgramRunnerTest {

    @Test
    void sandboxSafeOutputForwardsWithoutClosingCallerOwnedStream()
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicBoolean targetClosed = new AtomicBoolean();

        OutputStream target = new OutputStream() {
            @Override
            public void write(int value) {
                bytes.write(value);
            }

            @Override
            public void write(byte[] source, int offset, int length) {
                bytes.write(source, offset, length);
            }

            @Override
            public void close() {
                targetClosed.set(true);
            }
        };

        OutputStream redirected = LinkedProgramRunner.sandboxSafeOutput(target);
        assertNotSame(target, redirected,
                "UNTRUSTED sandbox must not receive System.out/System.err directly");

        redirected.write("ores".getBytes(StandardCharsets.UTF_8));
        redirected.flush();
        redirected.close();

        assertEquals("ores", bytes.toString(StandardCharsets.UTF_8));
        assertFalse(targetClosed.get(),
                "closing a Context must not close a caller-owned launcher stream");
    }

    @Test
    void sandboxSafeOutputRejectsNullTarget() {
        assertThrows(
                NullPointerException.class,
                () -> LinkedProgramRunner.sandboxSafeOutput(null));
    }
    @Test
    void asyncMainAndCustomSchedulerStayInsideLanguageRuntime() throws Exception {
        Path source = Files.createTempFile("ores-linked-async-main-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try {
            Files.writeString(source, """
                    pub async routine main() => void {
                      val scheduler = new OresScheduler(2);
                      val work = scheduler.start(async || -> {
                        val ready = Future.from_callback<int>(|cb| -> {
                          cb.resolve(42);
                          return;
                        });
                        return await ready;
                      });
                      val answer = await work;
                      stdio.println(answer);
                      scheduler.close();
                      return;
                    }
                    """);

            assertDoesNotThrow(() -> LinkedProgramRunner.run(
                    source,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    out,
                    err));

            assertTrue(
                    out.toString(StandardCharsets.UTF_8).contains("42"),
                    () -> "expected async main output, stderr="
                            + err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(source);
        }
    }


    @Test
    void bundledStdlibImportsExecuteThroughNormalLinker() throws Exception {
        Path source = Files.createTempFile("ores-linked-stdlib-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try {
            Files.writeString(source, """
                    import module http_wire from "std/net/http";

                    pub routine main() => void {
                      stdio.println(http_wire.is_redirect(302));
                      stdio.println(http_wire.default_port("https").unwrap());
                      stdio.println(http_wire.default_port("ftp").is_none());
                      return;
                    }
                    """);

            IncrementalCompiler.BuildResult build = assertDoesNotThrow(
                    () -> LinkedProgramRunner.run(
                            source,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            out,
                            err));

            assertTrue(build.units().containsKey("std/net/http.ores"));
            String output = out.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("true"), () -> "expected stdlib bool output, stderr="
                    + err.toString(StandardCharsets.UTF_8));
            assertTrue(output.contains("443"), () -> "expected stdlib default port, stderr="
                    + err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(source);
        }
    }

}
