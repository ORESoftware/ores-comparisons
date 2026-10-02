package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class DemoPr9SiblingImportTest {
    @Test
    void compilesExactSiblingImportDemoAsOneCodeUnitSet() throws Exception {
        Path root = Path.of("demo-pr9-imports");
        Map<String, String> units = new LinkedHashMap<>();
        units.put("app.ores", Files.readString(root.resolve("app.ores")));
        units.put("lib.ores", Files.readString(root.resolve("lib.ores")));
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(units));
    }
}
