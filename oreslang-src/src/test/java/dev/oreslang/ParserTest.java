package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ParserTest {
    @Test
    void supportsMultipleModulesAndComplexNumbers() {
        String source = """
                define module math
                  fnc z() => complex {
                    return 3 + 4i;
                  }
                end

                define module app
                  pub fnc main() => void {
                    const answer = 40 + 2;
                    [const first, let second] = [1, 2];
                    stdio.println("oreslang");
                    return;
                  }
                end
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(source));
        assertEquals(2, program.modules().size());
        assertEquals("math", program.modules().getFirst().name());
    }

    @Test
    void parsesIfDoFiWithCommaAndPipeConditions() {
        String source = """
                define module app
                  fnc choose(bool a, bool b) => int {
                    if a, b | false; do
                      return 1;
                    else
                      return 0;
                    fi
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void methodReceiverIsImplicitOrExplicitSelf() {
        String source = """
                define class x
                  @Ret<self>
                  find() {
                    return self;
                  }

                  @Ret<self>
                  find_with_arg(self x)(int foo) {
                    return self;
                  }
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow()
                .declarations()
                .getFirst();
        assertNull(klass.methods().getFirst().explicitReceiverType());
        assertEquals("x", klass.methods().get(1).explicitReceiverType().name());
    }


    @Test
    void classesAndModulesMustBeFileTopLevel() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Bar
                end

                define module Foo
                end
                """));

        IllegalArgumentException nestedModuleInFunction = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc init() => void {
                  for (let i = 0; i < 10; i = i + 1) {
                    define module Foo
                    end
                  }
                }
                """));
        assertTrue(nestedModuleInFunction.getMessage().contains("must be top-level"));

        IllegalArgumentException nestedClassInFunction = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc init() => void {
                  define class Bar
                  end
                }
                """));
        assertTrue(nestedClassInFunction.getMessage().contains("must be top-level"));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module Outer
                  define class Bar
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Outer
                  define class Inner
                  end
                end
                """));
    }

    @Test
    void lexerRecognizesLambdaAndFatReturnArrows() {
        var tokens = new Lexer("(int x) -> x + 1; fnc f() => int { return 1; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }
}
