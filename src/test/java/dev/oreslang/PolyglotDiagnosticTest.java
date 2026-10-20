package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class PolyglotDiagnosticTest {

    @Test
    void directContextEvalExposesExactLogicalGuestStack() throws Exception {
        String program = """
                fnc leaf() => int {
                  val zero = 0;
                  return 7 / zero;
                }

                fnc middle() => int {
                  return leaf() + 1;
                }

                pub routine main() => void {
                  val value = middle();
                  stdio.println(value);
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "direct-stack.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(source));

            assertTrue(failure.isGuestException(), failure::toString);
            assertFalse(failure.isInternalError(), failure::toString);

            Value guest = failure.getGuestObject();
            assertNotNull(guest);
            assertEquals("oreslang.error.v1", guest.getMember("schema").asString());
            assertEquals("ArithmeticException", guest.getMember("name").asString());

            Value stack = guest.getMember("guest_stack");
            assertTrue(stack.hasArrayElements());
            assertEquals(3, stack.getArraySize());

            Value leaf = stack.getArrayElement(0);
            assertEquals("leaf", leaf.getMember("symbol").asString());

            Value middle = stack.getArrayElement(1);
            assertEquals("middle", middle.getMember("symbol").asString());
            assertEquals("direct-stack.ores", middle.getMember("source").asString());
            assertEquals(7, middle.getMember("line").asLong());
            assertEquals(14, middle.getMember("column").asLong());

            Value main = stack.getArrayElement(2);
            assertEquals("main", main.getMember("symbol").asString());
            assertEquals(11, main.getMember("line").asLong());
            assertEquals(21, main.getMember("column").asLong());

            Set<String> polyglotNames = new LinkedHashSet<>();
            for (PolyglotException.StackFrame frame : failure.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) polyglotNames.add(frame.getRootName());
            }
            assertTrue(polyglotNames.contains("leaf"), polyglotNames::toString);
            assertTrue(polyglotNames.contains("middle"), polyglotNames::toString);
            assertTrue(polyglotNames.contains("main"), polyglotNames::toString);
        }
    }

    @Test
    void directContextEvalExposesAllAsyncCausalTraces() throws Exception {
        String program = """
                async fnc leaf() => int {
                  val scheduler = new OresScheduler(1);
                  val doomed = scheduler.start(|| -> {
                    val zero = 0;
                    return 9 / zero;
                  });
                  return await doomed;
                }

                async fnc middle() => int {
                  val value = await leaf();
                  return value + 1;
                }

                pub async routine main() => void {
                  val value = await middle();
                  stdio.println(value);
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "direct-async-stack.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(source));

            assertTrue(failure.isGuestException(), failure::toString);
            Value guest = failure.getGuestObject();
            assertNotNull(guest);

            Value traces = guest.getMember("async_traces");
            assertTrue(traces.hasArrayElements());
            assertTrue(traces.getArraySize() >= 1);

            Set<String> symbols = new LinkedHashSet<>();
            Set<String> kinds = new LinkedHashSet<>();
            for (long i = 0; i < traces.getArraySize(); i++) {
                Value frames = traces.getArrayElement(i).getMember("frames");
                for (long j = 0; j < frames.getArraySize(); j++) {
                    Value frame = frames.getArrayElement(j);
                    symbols.add(frame.getMember("symbol").asString());
                    kinds.add(frame.getMember("kind").asString());
                }
            }

            assertTrue(symbols.contains("leaf"), symbols::toString);
            assertTrue(symbols.contains("middle"), symbols::toString);
            assertTrue(symbols.contains("main"), symbols::toString);
            assertTrue(kinds.contains("await"), kinds::toString);
        }
    }

    @Test
    void directContextEvalPreservesPanicIdentityInGuestObject() throws Exception {
        String program = """
                fnc panic_leaf() => void {
                  None.unwrap();
                  return;
                }

                pub routine main() => void {
                  panic_leaf();
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "direct-panic.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(source));

            assertTrue(failure.isGuestException(), failure::toString);
            Value guest = failure.getGuestObject();
            assertNotNull(guest);
            assertEquals("OresPanic", guest.getMember("name").asString());
            assertTrue(guest.getMember("message").asString().contains("Option::unwrap"));

            Value stack = guest.getMember("guest_stack");
            Set<String> symbols = new LinkedHashSet<>();
            for (long i = 0; i < stack.getArraySize(); i++) {
                symbols.add(stack.getArrayElement(i).getMember("symbol").asString());
            }
            assertTrue(symbols.contains("panic_leaf"), symbols::toString);
            assertTrue(symbols.contains("main"), symbols::toString);
        }
    }
}
