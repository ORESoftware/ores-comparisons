package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimePointerTest {

    @Test
    void rtPtrAndDerefAcceptCommandAndParenthesizedForms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc ok() => void {
                  let Person person = new Person();
                  val Ptr<Person> a = rt ptr person;
                  val viewA = rt deref a;

                  val Ptr<Person> b = rt ptr(person);
                  val viewB = rt deref(b);

                  stdio.println(viewA.name);
                  stdio.println(viewB.name);
                  return;
                }
                """)));
    }

    @Test
    void rtPtrRoundTripsThroughOpaqueJvmHandle() throws Exception {
        String output = run("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc read_name(Ptr<Person> pointer) => String {
                  val view = rt deref pointer;
                  return view.name;
                }

                pub routine main() => void {
                  let Person person = new Person();
                  val Ptr<Person> pointer = rt ptr person;
                  stdio.stdout.write(read_name(pointer));
                  return;
                }
                """);

        assertTrue(output.equals("Alex"), output);
    }

    @Test
    void storedPointerKeepsOwnerReadBorrowed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub let String name = "Alex";
                        end

                        fnc rename(Person mut person) => void {
                          person.name = "Taylor";
                          return;
                        }

                        fnc bad() => void {
                          let Person person = new Person();
                          val Ptr<Person> pointer = rt ptr person;
                          rename(person);
                          stdio.println((rt deref pointer).name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }

    @Test
    void pointerCannotEscapeItsOwnerLifetime() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => Ptr<Person> {
                          let Person person = new Person();
                          return rt ptr person;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow")
                || error.getMessage().toLowerCase().contains("provenance"),
                error.getMessage());
    }

    @Test
    void derefRequiresPtr() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                        end

                        fnc bad(Person person) => void {
                          val value = rt deref person;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("Ptr<T>"), error.getMessage());
    }

    @Test
    void ptrRejectsCopyScalarsAndPtrScalarTypes() {
        IllegalArgumentException valueError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(int value) => void {
                          val pointer = rt ptr value;
                          return;
                        }
                        """)));

        assertTrue(valueError.getMessage().toLowerCase().contains("reference-backed")
                || valueError.getMessage().toLowerCase().contains("stable addresses"),
                valueError.getMessage());

        IllegalArgumentException typeError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Ptr<int> pointer) => void {
                          return;
                        }
                        """)));

        assertTrue(typeError.getMessage().contains("Ptr<T>")
                && typeError.getMessage().toLowerCase().contains("reference-backed"),
                typeError.getMessage());
    }

    @Test
    void pointerCapabilitiesCannotBeCopiedOrShared() {
        IllegalArgumentException copyError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                        end

                        fnc bad() => void {
                          let Person person = new Person();
                          val copied = rt copy(rt ptr person);
                          return;
                        }
                        """)));

        assertTrue(copyError.getMessage().contains("Ptr<T>"), copyError.getMessage());

        IllegalArgumentException shareError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                        end

                        fnc bad() => void {
                          let Person person = new Person();
                          val shared = rt share(rt ptr person);
                          return;
                        }
                        """)));

        assertTrue(shareError.getMessage().contains("Ptr<T>"), shareError.getMessage());
    }

    @Test
    void ptrIsCompilerOwnedButBarePtrNameIsNotMagic() {
        IllegalArgumentException redefine = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Ptr as
                        end
                        """)));

        assertTrue(redefine.getMessage().contains("built-in type 'Ptr'"), redefine.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ptr(int value) => int {
                  return value;
                }

                fnc ok() => int {
                  return ptr(7);
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rt-pointer.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
