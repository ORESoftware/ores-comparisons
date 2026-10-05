package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StandardLibraryResolverTest {

    @Test
    void bundledStdlibUnitsJoinTheNormalCompilationGraph() {
        String app = """
                import module http_wire from "std/net/http";

                define module app
                  pub fnc is_redirect(Int status) => Bool {
                    return http_wire.is_redirect(status);
                  }
                end
                """;

        IncrementalCompiler.BuildResult result =
                new IncrementalCompiler().compile(Map.of("app.ores", app));

        assertTrue(result.units().containsKey("app.ores"));
        assertTrue(result.units().containsKey("std/net/http.ores"));
        assertTrue(result.units().get("std/net/http.ores").sourceText()
                .contains("HTTP wire-policy helpers implemented in Oreslang"));
    }

    @Test
    void callerCannotShadowReservedStdlibNamespace() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "std/net/http.ores",
                        "define module fake end")));

        assertTrue(failure.getMessage().contains("reserved"));
    }

    @Test
    void traversalOutOfStdlibFailsClosed() {
        String app = """
                import module escaped from "std/../escaped";
                define module app
                end
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of("app.ores", app)));
    }

    @Test
    void unknownStdlibUnitFailsClosed() {
        String app = """
                import module missing from "std/does/not/exist";
                define module app
                end
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of("app.ores", app)));

        assertTrue(failure.getMessage().contains("unknown Oreslang standard-library import"));
    }
}
