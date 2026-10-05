package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class RoutineAndLoopTest {
    @Test
    void exactFncProgramCompilesAndRuns() throws Exception {
        String program = """
                define module x as
                  define class y as
                  end
                end

                pub fnc main() => void {
                  val y = new x.y();
                  stdio.stdout.write(y);
                }
                """;
        String output = run(program);
        assertTrue(output.contains("y{}"));
    }

    @Test
    void routineMainCompilesWithSafeSemicolonOmission() throws Exception {
        String program = """
                define module x as
                  define class y as
                  end
                end

                pub routine main() => void {
                  val y = new x.y();
                  stdio.stdout.write(y)
                }
                """;
        String output = run(program);
        assertTrue(output.contains("y{}"));
    }

    @Test
    void routinesCannotParticipateInRecursionButFncsCan() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                routine spin() => void {
                  spin();
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc recurse(bool stop) => void {
                  if stop; do
                    return;
                  else
                    recurse(true);
                    return;
                  fi
                }
                """)));
    }

    @Test
    void methodsOverloadOnlyByArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m as
                  define class C as
                    pub find() => int { return 0; }
                    pub find(int value) => int { return value; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module m as
                  define class C as
                    pub find(int value) => int { return value; }
                    pub find(String value) => int { return 1; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc find() => int { return 0; }
                fnc find(int value) => int { return value; }
                """)));
    }

    @Test
    void explicitlyTypedLambdasCanRecurse() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let Fnc<int, int> fact = |int n| -> {
                    return n == 0 ? 1 : n * fact(n - 1);
                  };
                  stdio.stdout.write(fact(5))
                }
                """);
        assertEquals("120", output);
    }

    @Test
    void ternaryWorksWithOption() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc find(bool found) => Option<int> {
                  return found ? Some(42) : None;
                }
                """)));
    }

    @Test
    void structuralParametersAreOptInAndSupportBrandedInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Bar {
                  markerBrand: 'marking/branding'
                }

                pub interface Foo extends Bar {
                }

                fnc structural(@Structural Foo value) => String {
                  return value.markerBrand;
                }

                fnc main() => void {
                  val branded = obj{markerBrand: "marking/branding"};
                  stdio.println(structural(branded));
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Foo {
                  markerBrand: 'marking/branding'
                }

                fnc nominal(Foo value) => String {
                  return "ok";
                }

                fnc main() => void {
                  val branded = obj{markerBrand: "marking/branding"};
                  stdio.println(nominal(branded));
                  return;
                }
                """)));
    }

    @Test
    void forOfInjectsSchedulerSafepoints() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for (val item of arr[1, 2, 3]) {
                    stdio.stdout.write(item);
                  }
                  stdio.stdout.write(process.descriptor.scheduler_safepoints)
                }
                """);
        assertTrue(output.startsWith("123"));
        assertTrue(output.endsWith("3"));
    }

    @Test
    void canonicalForOfDoDoneParsesAndRuns() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for item of arr[1, 2, 3] do
                    stdio.stdout.write(item)
                  done
                }
                """);

        assertEquals("123", output);
    }

    @Test
    void forOfDestructuresTupleElements() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for [key, value] of arr[(1, "a"), (2, "b")] do
                    stdio.stdout.write(key);
                    stdio.stdout.write(value)
                  done
                }
                """);

        assertEquals("1a2b", output);
    }

    @Test
    void forOfDestructuringPreservesTupleComponentTypesAcrossElements() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for [key, value] of arr[(1, "a"), (2, "b")] do
                    stdio.stdout.write(key + 10);
                    stdio.stdout.write(value + "!")
                  done
                }
                """);

        assertEquals("11a!12b!", output);
    }

    @Test
    void singleBindingBracketPatternStillDestructures() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for [value] of arr[arr[7], arr[8]] do
                    stdio.stdout.write(value)
                  done
                }
                """);

        assertEquals("78", output);
    }

    @Test
    void forOfDestructuringRejectsDuplicateBindingNames() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad() => void {
                          for [item, item] of arr[(1, 2)] do
                            return;
                          done
                        }
                        """));

        String message = error.getMessage().toLowerCase();
        assertTrue(message.contains("duplicate") && message.contains("item"),
                error.getMessage());
    }

    @Test
    void forOfDestructuringArityIsCheckedStatically() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          for [a, b, c] of arr[(1, 2)] do
                            stdio.println(a)
                          done
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("arity"), error.getMessage());
    }

    @Test
    void forOfDestructuringDoesNotUpgradeBorrowedComponentsToMutableOwners() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;
                        end

                        fnc mutate(Box mut box) => void {
                          box.value = box.value + 1;
                          return;
                        }

                        fnc bad() => void {
                          let Box left = new Box(1);
                          let Box right = new Box(2);
                          let pairs = arr[(left, right)];
                          for [a, b] of pairs do
                            mutate(a);
                          done
                          return;
                        }
                        """)));

        String message = error.getMessage().toLowerCase();
        assertTrue(message.contains("borrow") || message.contains("mut"),
                error.getMessage());
    }

    @Test
    void takenForOfTransfersNonCopyTupleComponents() throws Exception {
        String output = run("""
                define class Box as
                  pub val int value = 0;
                end

                pub routine main() => void {
                  let pairs = arr[(new Box(1), new Box(2))];
                  for [left, right] of rt take pairs do
                    stdio.stdout.write(left.value);
                    stdio.stdout.write(right.value)
                  done
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void takenForOfInvalidatesOriginalIterable() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 0;
                        end

                        fnc bad() => void {
                          let pairs = arr[(new Box(1), new Box(2))];
                          for [left, right] of rt take pairs do
                            stdio.println(left.value);
                          done
                          stdio.println(pairs);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("moved")
                || error.getMessage().toLowerCase().contains("take"),
                error.getMessage());
    }

    @Test
    void destructuredLetBorrowCanRebindWithoutMutatingSourceProjection() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 0;
                end

                pub routine main() => void {
                  let pairs = arr[(new Box(1), new Box(2))];
                  for [let a, b] of pairs do
                    a = new Box(9);
                    stdio.stdout.write(a.value);
                    stdio.stdout.write(b.value)
                  done

                  // Every iteration borrow must be released, including the
                  // alias ended early by rebinding a.
                  pairs = arr[(new Box(3), new Box(4))];
                }
                """);

        assertEquals("92", output);
    }

    @Test
    void parenthesizedForCanUseDoDoneBody() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for (let i = 0; i < 3; i = i + 1) do
                    stdio.stdout.write(i)
                  done
                }
                """);

        assertEquals("012", output);
    }

    @Test
    void conventionalForLoopAlsoInjectsSafepoints() throws Exception {
        String output = run("""
                pub routine main() => void {
                  for (let i = 0; i < 3; i = i + 1) {
                    stdio.stdout.write(i);
                  }
                  stdio.stdout.write(process.descriptor.scheduler_safepoints)
                }
                """);
        assertEquals("0123", output);
    }

    @Test
    void customJavascriptStyleIteratorDrivesForOf() throws Exception {
        String output = run("""
                define module collections as
                  define class Bag as
                    [Symbol.iterator]() => Array<int> {
                      return arr[4, 5];
                    }
                  end
                end

                pub routine main() => void {
                  val bag = new collections.Bag();
                  for (val item of bag) {
                    stdio.stdout.write(item)
                  }
                }
                """);
        assertEquals("45", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "together.ores")
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
