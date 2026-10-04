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

final class RuntimeOwnershipSyntaxTest {

    @Test
    void rtOwnershipOperationsAcceptCommandAndParenthesizedForms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc copy_command(int value) => int {
                  return rt copy value;
                }

                fnc copy_call(int value) => int {
                  return rt copy(value);
                }
                """)));
    }

    @Test
    void ordinaryClassArgumentPassesSameReferenceWithoutMovingCaller() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc read_name(Person person) => String {
                  return person.name;
                }

                fnc ok() => void {
                  let Person p = new Person();
                  stdio.println(read_name(p));
                  stdio.println(read_name(p));
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void ordinaryReferenceParameterCannotMutateTheCallerObject() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub let String name = "Alex";
                        end

                        fnc rename(Person person) => void {
                          person.name = "Taylor";
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("mut")
                || error.getMessage().toLowerCase().contains("borrow")
                || error.getMessage().toLowerCase().contains("mutable"),
                error.getMessage());
    }

    @Test
    void mutableParameterMutatesTheSameReferencedObject() throws Exception {
        String output = run("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                pub routine main() => void {
                  let Person p = new Person();
                  rename(p);
                  stdio.stdout.write(p.name);
                  return;
                }
                """);

        assertTrue(output.equals("Taylor"), output);
    }

    @Test
    void typeMutNameIsCanonicalMutableReferenceSyntax() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                fnc ok() => void {
                  let Person p = new Person();
                  rename(p);
                  rename(p);
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void pipeLambdaAcceptsCanonicalTypeMutNameSyntax() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc make() => void {
                  val rename = |Person mut person| -> {
                    person.name = "Taylor";
                    return;
                  };
                  return;
                }
                """));
    }

    @Test
    void pipeLambdaRejectsDuplicateMutMarkers() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Person as
                        end

                        fnc bad() => void {
                          val f = |mut Person mut person| -> {
                            return;
                          };
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("duplicate mut"), error.getMessage());
    }

    @Test
    void rtBorrowUsesSameReferenceAndBlocksOverlappingMutation() {
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
                          let Person p = new Person();
                          val view = rt borrow p;
                          rename(p);
                          stdio.println(view.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }

    @Test
    void rtTakeTransfersOwnershipAndRejectsLaterCallerUse() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          let Person owned = rt take p;
                          stdio.println(owned.name);
                          stdio.println(p.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"), error.getMessage());
    }

    @Test
    void pointerStyleBorrowSyntaxRemainsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Person as
                        end

                        fnc bad(&Person person) => void {
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("pointer-style '&'"), error.getMessage());
    }

    @Test
    void rtCopyUsesExplicitClassCopyContractAndProducesIndependentIdentity() throws Exception {
        String output = run("""
                define class Person as
                  pub let String name = "Alex";

                  pub copy() => self {
                    return new Person();
                  }
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                pub routine main() => void {
                  let Person original = new Person();
                  let Person copied = rt copy original;
                  rename(copied);
                  stdio.stdout.write(original.name);
                  stdio.stdout.write(":");
                  stdio.stdout.write(copied.name);
                  return;
                }
                """);

        assertTrue(output.equals("Alex:Taylor"), output);
    }

    @Test
    void rtCopyRecursesThroughStructuralRecordFields() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 7;

                  pub copy() => self {
                    return new Box(self.value);
                  }
                end

                pub routine main() => void {
                  let original = obj{child: new Box(7)};
                  let copied = rt copy original;
                  copied.child.value = 9;
                  stdio.stdout.write(original.child.value);
                  stdio.stdout.write(copied.child.value);
                  return;
                }
                """);

        assertTrue(output.equals("79"), output);
    }

    @Test
    void rtCopyRejectsIdentityClassWithoutCopyContract() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          let Person q = rt copy p;
                          stdio.println(q.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("copy"), error.getMessage());
    }

    @Test
    void rtShareDoesNotRequireCopyContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc ok() => void {
                  let Person p = new Person();
                  val shared = rt share p;
                  stdio.println(shared.name);
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void rtShareRemovesUniqueMutationAuthorityFromOriginalOwner() {
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
                          let Person p = new Person();
                          val shared = rt share p;
                          stdio.println(shared.name);
                          rename(p);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("shared")
                || error.getMessage().toLowerCase().contains("ownership"), error.getMessage());
    }

    @Test
    void rtShareAliasCannotBeMutatedOrTaken() {
        IllegalArgumentException mutate = assertThrows(
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
                          let Person p = new Person();
                          val shared = rt share p;
                          rename(shared);
                          return;
                        }
                        """)));
        assertTrue(mutate.getMessage().toLowerCase().contains("shared")
                || mutate.getMessage().toLowerCase().contains("mut"), mutate.getMessage());

        IllegalArgumentException take = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          val shared = rt share p;
                          val owned = rt take shared;
                          return;
                        }
                        """)));
        assertTrue(take.getMessage().toLowerCase().contains("shared")
                || take.getMessage().toLowerCase().contains("ownership"), take.getMessage());
    }

    @Test
    void rtShareCanBeDuplicatedAsSharedOwnership() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc ok() => void {
                  let Person p = new Person();
                  val first = rt share p;
                  val second = rt share p;
                  stdio.println(first.name);
                  stdio.println(second.name);
                  return;
                }
                """)));
    }

    @Test
    void sharedOwnershipCannotBeLaunderedThroughReturnOrAggregate() {
        IllegalArgumentException returned = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => Person {
                          let Person p = new Person();
                          return rt share p;
                        }
                        """)));
        assertTrue(returned.getMessage().contains("Shared<T>")
                || returned.getMessage().toLowerCase().contains("shared ownership"),
                returned.getMessage());

        IllegalArgumentException stored = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          val shared = rt share p;
                          val wrapped = obj{person: shared};
                          return;
                        }
                        """)));
        assertTrue(stored.getMessage().contains("Shared<T>")
                || stored.getMessage().toLowerCase().contains("shared ownership"),
                stored.getMessage());
    }

    @Test
    void borrowingASharedOwnerRemainsNonOwning() {
        IllegalArgumentException returnedBorrow = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => Person {
                          let Person p = new Person();
                          val shared = rt share p;
                          return rt borrow shared;
                        }
                        """)));

        String message = returnedBorrow.getMessage().toLowerCase();
        assertTrue(message.contains("borrow") && message.contains("return"),
                returnedBorrow.getMessage());
    }

    @Test
    void rtParenthesesCanGroupWiderExpression() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc grouped(int left, int right) => int {
                  return rt copy(left + right);
                }

                fnc unary_precedence(int left, int right) => int {
                  return rt copy left + right;
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rt-ownership.ores")
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
