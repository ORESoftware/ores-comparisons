package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CompositionalValuesTest {

    @Test
    void questionMarkPropagatesOptionAndResultWithoutBreakingTernary() throws Exception {
        String program = """
                fnc plus_one(Option<int> value) => Option<int> {
                  val n = value?;
                  return Some(n + 1);
                }

                fnc result_plus_one(Result<int, String> value) => Result<int, String> {
                  val n = value?;
                  return Ok(n + 1);
                }

                pub fnc main() => void {
                  stdio.println(plus_one(Some(4)).unwrap());
                  stdio.println(plus_one(None).is_none());
                  stdio.println(result_plus_one(Ok(6)).unwrap());
                  stdio.println(result_plus_one(Err("bad")).is_err());
                  stdio.println(true ? 11 : 22);
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = run(program, "try-propagation.ores");
        assertTrue(output.contains("5"));
        assertTrue(output.contains("7"));
        assertTrue(output.contains("11"));
        assertTrue(output.lines().filter("true"::equals).count() >= 2);
    }

    @Test
    void questionMarkRequiresCompatibleEnclosingResultAndIsForbiddenInDefer() {
        IllegalArgumentException wrongReturn = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Option<int> value) => int {
                          return value?;
                        }
                        """)));
        assertTrue(wrongReturn.getMessage().contains("return Option"));

        IllegalArgumentException wrongError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Result<int, String> value) => Result<int, bool> {
                          val n = value?;
                          return Ok(n);
                        }
                        """)));
        assertTrue(wrongError.getMessage().contains("propagated Result error"));

        IllegalArgumentException deferred = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Option<int> value) => Option<int> {
                          defer value?;
                          return Some(1);
                        }
                        """)));
        assertTrue(deferred.getMessage().contains("defer"));
    }

    @Test
    void propagationStillRunsLexicalDeferInSyncAndAsyncCallables() throws Exception {
        String program = """
                fnc sync_fail(Option<int> value) => Option<int> {
                  defer stdio.println("sync-cleanup");
                  val n = value?;
                  return Some(n);
                }

                async fnc async_fail(Option<int> value) => Option<int> {
                  defer stdio.println("async-cleanup");
                  val n = value?;
                  return Some(n);
                }

                pub async routine main() => void {
                  stdio.println(sync_fail(None).is_none());
                  val async_result = await async_fail(None);
                  stdio.println(async_result.is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = run(program, "propagation-defer.ores");
        assertTrue(output.contains("sync-cleanup"));
        assertTrue(output.contains("async-cleanup"));
        assertTrue(output.lines().filter("true"::equals).count() >= 2);
    }

    @Test
    void optionAndResultCompositionObeyCoreLawsAndAliases() throws Exception {
        String program = """
                pub fnc main() => void {
                  val Option<int> m = Some(2);

                  val left = Some(2).flat_map<int>(|x| -> {
                    return Some(x + 3);
                  });
                  stdio.println(left.unwrap() == 5);

                  val right = m.flat_map<int>(|x| -> {
                    return Some(x);
                  });
                  stdio.println(right == m);

                  val assoc_left = m
                    .flat_map<int>(|x| -> { return Some(x + 3); })
                    .flat_map<int>(|x| -> { return Some(x * 2); });
                  val assoc_right = m.flat_map<int>(|x| -> {
                    return Some(x + 3).flat_map<int>(|y| -> {
                      return Some(y * 2);
                    });
                  });
                  stdio.println(assoc_left == assoc_right);

                  val mapped = Some(20).map<int>(|x| -> { return x + 1; });
                  stdio.println(mapped.unwrap());

                  val Option<int> none = None;
                  val recovered = none.or_else(|| -> { return Some(9); });
                  stdio.println(recovered.unwrap());

                  val Result<int, String> ok = Ok(10);
                  val result_mapped = ok.map<int>(|x| -> { return x + 2; });
                  stdio.println(result_mapped.unwrap());

                  val Result<int, String> err = Err("bad");
                  val remapped = err.map_err<int>(|message| -> {
                    return 7;
                  });
                  stdio.println(remapped.is_err());

                  val recovered_result = err.or_else<String>(|message| -> {
                    return Ok(33);
                  });
                  stdio.println(recovered_result.unwrap());

                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = run(program, "composition-laws.ores");
        assertTrue(output.lines().filter("true"::equals).count() >= 4);
        assertTrue(output.contains("21"));
        assertTrue(output.contains("9"));
        assertTrue(output.contains("12"));
        assertTrue(output.contains("33"));
    }

    @Test
    void futureMapFlatMapAndFlattenStayAwaitable() throws Exception {
        String program = """
                pub async routine main() => void {
                  val first = Future.from_callback<int>(|cb| -> {
                    cb.resolve(20);
                    return;
                  });

                  val mapped = first.map<int>(|x| -> {
                    return x + 1;
                  });

                  val chained = mapped.flat_map<int>(|x| -> {
                    return Future.from_callback<int>(|cb| -> {
                      cb.resolve(x * 2);
                      return;
                    });
                  });

                  stdio.println(await chained);

                  val outer = Future.from_callback<Future<int>>(|cb| -> {
                    cb.resolve(Future.from_callback<int>(|inner| -> {
                      inner.resolve(8);
                      return;
                    }));
                    return;
                  });
                  stdio.println(await outer.flatten());
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = run(program, "future-composition.ores");
        assertTrue(output.contains("42"));
        assertTrue(output.contains("8"));
    }

    @Test
    void iteratorAndStreamComposeAndFlatten() throws Exception {
        String program = """
                pub async routine main() => void {
                  val Iterator<int> iterator = Iterator.from_values([1, 2])
                    .map<int>(|x| -> { return x * 10; })
                    .flat_map<int>(|x| -> {
                      return Iterator.from_values([x, x + 1]);
                    });

                  stdio.println(iterator.next().unwrap());
                  stdio.println(iterator.next().unwrap());
                  stdio.println(iterator.next().unwrap());
                  stdio.println(iterator.next().unwrap());
                  stdio.println(iterator.next().is_none());

                  val Iterator<Iterator<int>> nested_iterator =
                    Iterator.from_values([
                      Iterator.from_values([5]),
                      Iterator.from_values([6])
                    ]);
                  val flat_iterator = nested_iterator.flatten();
                  stdio.println(flat_iterator.next().unwrap());
                  stdio.println(flat_iterator.next().unwrap());

                  val Stream<int> stream = Stream.from_values([2, 3])
                    .map<int>(|x| -> { return x * 2; })
                    .flat_map<int>(|x| -> {
                      return Stream.from_values([x, x + 1]);
                    });

                  stdio.println((await stream.next()).unwrap());
                  stdio.println((await stream.next()).unwrap());
                  stdio.println((await stream.next()).unwrap());
                  stdio.println((await stream.next()).unwrap());
                  stdio.println((await stream.next()).is_none());

                  val one = Future.from_callback<int>(|cb| -> {
                    cb.resolve(99);
                    return;
                  });
                  val from_future = Stream.from_future(one);
                  stdio.println((await from_future.next()).unwrap());
                  stdio.println((await from_future.next()).is_none());

                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = run(program, "iterator-stream.ores");
        for (String expected : new String[]{"10", "11", "20", "21", "5", "6", "4", "5", "6", "7", "99"}) {
            assertTrue(output.contains(expected), () -> "missing " + expected + " in " + output);
        }
        assertTrue(output.lines().filter("true"::equals).count() >= 3);
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
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
