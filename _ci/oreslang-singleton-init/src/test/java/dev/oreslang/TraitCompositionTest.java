package dev.oreslang;

import dev.oreslang.ast.Ast;
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

final class TraitCompositionTest {
    @Test
    void interfacesAreStorageFreeContracts() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define interface Bad as
                          val int state;
                        end
                        """));

        assertTrue(error.getMessage().contains("storage-free contracts"));
    }

    @Test
    void traitCanCarryPrivateStateBehaviorAndAnInterfaceContract() {
        Ast.Program checked = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define interface CounterApi as
                    fnc bump() => int;
                  end

                  define trait Counter is CounterApi as
                    private let int count = 10;

                    pub bump() => int {
                      self.count = self.count + 1;
                      return self.count;
                    }
                  end

                  define class Box with Counter as
                    pub val int value;
                  end
                end
                """)));

        Ast.ModuleDecl model = checked.modules().stream()
                .filter(module -> module.name().equals("model"))
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl box = model.declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(klass -> klass.name().equals("Box"))
                .findFirst()
                .orElseThrow();

        assertTrue(box.traits().isEmpty());
        assertTrue(box.interfaces().stream().anyMatch(type -> type.name().equals("CounterApi")));
        assertTrue(box.fields().stream().anyMatch(field ->
                field.name().equals("count") && field.composed()));
        assertTrue(box.methods().stream().anyMatch(method ->
                method.name().equals("bump") && method.composed()));
    }

    @Test
    void traitStateIsNotAPositionalConstructorParameter() throws Exception {
        String program = """
                define module model as
                  define trait Counter as
                    private let int count = 10;

                    pub bump() => int {
                      self.count = self.count + 1;
                      return self.count;
                    }
                  end

                  define class Box with Counter as
                    pub val int value;
                  end
                end

                define module app as
                  pub fnc main() => void {
                    val box = new Box(5);
                    stdio.println(box.value);
                    stdio.println(box.bump());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "traits.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("5"));
        assertTrue(text.contains("11"));
    }

    @Test
    void multipleConcreteTraitMethodsRequireExplicitClassResolution() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait A as
                            pub value() => int { return 1; }
                          end

                          define trait B as
                            pub value() => int { return 2; }
                          end

                          define class Bad with A, B as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous trait method"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait A as
                    pub value() => int { return 1; }
                  end

                  define trait B as
                    pub value() => int { return 2; }
                  end

                  define class Good with A, B as
                    pub value() => int { return 3; }
                  end
                end
                """)));
    }

    @Test
    void traitRequirementsMustBeImplementedByConcreteClasses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Loads as
                            pub abstract load() => int;

                            pub read() => int {
                              return self.load();
                            }
                          end

                          define class Bad with Loads as
                          end
                        end
                        """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait Loads as
                    pub abstract load() => int;

                    pub read() => int {
                      return self.load();
                    }
                  end

                  define class Good with Loads as
                    pub load() => int { return 42; }
                  end
                end
                """)));
    }

    @Test
    void privateTraitStateStaysLexicallyOwnedByTheTrait() {
        IllegalArgumentException leak = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Secret as
                            private val int secret = 7;
                            pub read() => int { return self.secret; }
                          end

                          define class Bad with Secret as
                            pub leak() => int { return self.secret; }
                          end
                        end
                        """)));

        assertTrue(leak.getMessage().contains("private to that trait"));

        IllegalArgumentException hiddenDependency = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait HostReader as
                            pub read() => int { return self.host_value; }
                          end

                          define class Bad with HostReader as
                            pub val int host_value = 9;
                          end
                        end
                        """)));

        assertTrue(hiddenDependency.getMessage().contains("undeclared self member"));
    }
}
