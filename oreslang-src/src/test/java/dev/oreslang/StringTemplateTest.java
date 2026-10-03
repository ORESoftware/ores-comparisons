package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StringTemplateTest {
    @Test
    void parsesSingleDoubleAndTemplateStrings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  val doubleQuoted = "single ' quote";
                  val singleQuoted = 'double " quote';
                  val escaped = 'it\\'s "fine"';
                  val who = "Ores";
                  val count = 2;
                  val message = `hello ${who}: ${count + 1}`;
                  val nested = `outer ${`inner ${count}`}`;
                  val multiline = `first line
                second line: ${who}`;
                  stdio.println(doubleQuoted);
                  stdio.println(singleQuoted);
                  stdio.println(escaped);
                  stdio.println(message);
                  stdio.println(nested);
                  stdio.println(multiline);
                  return;
                }
                """)));
    }

    @Test
    void ordinaryQuotedStringsRejectRawNewlines() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main() => void {
                  val invalid = "first
                second";
                  return;
                }
                """));
    }

    @Test
    void capabilityChecksReachTemplateInterpolations() {
        SecurityException denied = assertThrows(SecurityException.class, () ->
                CapabilityChecker.check(Parser.parse("""
                        pub fnc main() => void {
                          val descriptor = `context ${process.context_id}`;
                          return;
                        }
                        """), IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));
    }

    @Test
    void executesInterpolationMultilineNestingAndNestedBraces() throws Exception {
        String program = """
                pub fnc main() => void {
                  val who = "Ores";
                  val count = 2;
                  val object = obj{name: 'lang'};
                  stdio.println("single ' inside double");
                  stdio.println('double " inside single');
                  stdio.println(`hello ${who}: ${count + 3}`);
                  stdio.println(`member ${object.name}`);
                  stdio.println(object.name);
                  stdio.println(`direct ${obj{name: "brace"}.name}`);
                  stdio.println(`comment ${/* nested scanner comment */ count + 2}`);
                  stdio.println(`outer ${`inner ${count + 1}`}`);
                  stdio.println(`line one
                line two: ${who}`);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "strings-and-templates.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("single ' inside double"));
        assertTrue(text.contains("double \" inside single"));
        assertTrue(text.contains("hello Ores: 5"));
        assertTrue(text.contains("member lang"));
        assertTrue(text.contains("brace"));
        assertTrue(text.contains("comment 4"));
        assertTrue(text.contains("outer inner 3"));
        assertTrue(text.contains("line one\nline two: Ores"));
    }
}
