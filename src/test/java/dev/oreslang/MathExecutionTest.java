package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MathExecutionTest {
    @TempDir
    Path tempDir;

    @Test
    void matrixVectorSyntaxExecutesThroughTheLinkedRuntime() throws Exception {
        Path source = tempDir.resolve("math.ores");
        Files.writeString(source, """
                pub routine main() => void {
                  val a = matrix[[1.0, 2.0], [3.0, 4.0]];
                  val x = vector[5.0, 6.0];
                  val y = a @ x;
                  stdio.stdout.write(y.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(y.get(1));
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals("17.0|39.0", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void kotlinStyleFunctionAliasExecutesAcrossLinkedCodeUnits() throws Exception {
        Path library = tempDir.resolve("solver.ores");
        Path source = tempDir.resolve("main.ores");

        Files.writeString(library, """
                pub fnc solve(int value) => int {
                  return value + 1;
                }
                """);

        Files.writeString(source, """
                import fnc {solve as linear_solve} from "./solver.ores";

                pub routine main() => void {
                  stdio.stdout.write(linear_solve(41));
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }
}
