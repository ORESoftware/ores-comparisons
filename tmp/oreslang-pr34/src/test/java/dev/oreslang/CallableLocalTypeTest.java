package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CallableLocalTypeTest {
    @Test
    void callableLocalStructCanDefineAnEscapingReturnShapeWithoutLeakingItsName() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc getat() => T {
                    struct T {
                      foo: string
                    }

                    return obj{
                      foo: 'hello'
                    };
                  }

                  pub fnc use_it() => string {
                    val result = getat();
                    return result.foo;
                  }
                end
                """)));

        IllegalArgumentException leaked = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          pub fnc getat() => T {
                            struct T { foo: string }
                            return obj{foo: "hello"};
                          }

                          fnc misuse() => void {
                            val T leaked = getat();
                            return;
                          }
                        end
                        """)));
        assertTrue(leaked.getMessage().contains("initializer for leaked"));
    }

    @Test
    void sameLocalTypeNameIsNominallyDistinctAcrossCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc left() => T {
                    struct T { left: string }
                    return obj{left: "L"};
                  }

                  fnc right() => T {
                    struct T { right: int }
                    return obj{right: 7};
                  }

                  pub fnc main() => void {
                    val a = left();
                    val b = right();
                    stdio.println(a.left);
                    stdio.println(b.right);
                    return;
                  }
                end
                """)));
    }

    @Test
    void objectLiteralConversionToStructIsExactAtTypedBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc missing() => T {
                    struct T { foo: string; count: int }
                    return obj{foo: "hello"};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc extra() => T {
                    struct T { foo: string }
                    return obj{foo: "hello", extra: 1};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc wrong() => T {
                    struct T { foo: string }
                    return obj{foo: 42};
                  }
                end
                """)));
    }

    @Test
    void localInterfaceRemainsAStorageFreeMethodContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc use_contract() => void {
                    interface LocalApi {
                      fnc ping() => int;
                    }
                    return;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app as
                          fnc bad_contract() => void {
                            interface LocalApi {
                              value: int;
                            }
                            return;
                          }
                        end
                        """));
        assertTrue(error.getMessage().contains("storage-free contracts"));
    }

    @Test
    void shortAndLongStructFormsBothWorkInsideCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc short_form() => T {
                    struct T { foo: string }
                    return obj{foo: "short"};
                  }

                  fnc long_form() => T {
                    define struct T as
                      foo: string
                    end
                    return obj{foo: "long"};
                  }
                end
                """)));
    }

    @Test
    void runtimePromotesReturnedObjToLocalNominalStructSoMethodsStillDispatch() throws Exception {
        String program = """
                define module app as
                  fnc getat() => T {
                    struct T {
                      foo: string;

                      pub greeting() => string {
                        return self.foo + " world";
                      }
                    }

                    return obj{foo: "hello"};
                  }

                  pub fnc main() => void {
                    val result = getat();
                    stdio.println(result.foo);
                    stdio.println(result.greeting());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "local-types.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("hello"));
        assertTrue(text.contains("hello world"));
    }
}
