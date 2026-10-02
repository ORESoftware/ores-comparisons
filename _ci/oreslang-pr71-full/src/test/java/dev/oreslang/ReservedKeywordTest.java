package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

final class ReservedKeywordTest {
    private static final List<String> RESERVED = List.of(
            "of", "is", "as", "actor", "isolate", "recover", "panic", "throw", "raise", "stop");

    @Test
    void lexerAlwaysClassifiesOfIsAsAsKeywords() {
        var tokens = new Lexer("of is as").scan();

        assertEquals(Token.Type.OF, tokens.get(0).type());
        assertEquals(Token.Type.IS, tokens.get(1).type());
        assertEquals(Token.Type.AS, tokens.get(2).type());
    }

    @Test
    void reservedWordsCannotBeLocalBindings() {
        assertRejectedEverywhere(keyword -> """
                pub routine main() => void {
                  val %s = 1;
                }
                """.formatted(keyword));
    }

    @Test
    void reservedWordsCannotBeParameterNames() {
        assertRejectedEverywhere(keyword -> """
                fnc f(int %s) => void {
                  return;
                }
                """.formatted(keyword));
    }

    @Test
    void reservedWordsCannotBeCallableNames() {
        assertRejectedEverywhere(keyword -> """
                fnc %s() => void {
                  return;
                }
                """.formatted(keyword));
    }

    @Test
    void reservedWordsCannotBeModuleClassMethodOrFieldNames() {
        for (String keyword : RESERVED) {
            assertReservedFailure("""
                    define module %s
                    end
                    """.formatted(keyword), keyword);

            assertReservedFailure("""
                    define class %s
                    end
                    """.formatted(keyword), keyword);

            assertReservedFailure("""
                    define class Holder
                      %s() => void {
                        return;
                      }
                    end
                    """.formatted(keyword), keyword);

            assertReservedFailure("""
                    define class Holder
                      let int %s = 1;
                    end
                    """.formatted(keyword), keyword);
        }
    }

    @Test
    void reservedWordsCannotBeTypeAliasGenericNamespaceOrImportAliasNames() {
        for (String keyword : RESERVED) {
            assertReservedFailure("""
                    type %s = int;
                    """.formatted(keyword), keyword);

            assertReservedFailure("""
                    fnc f<%s>() => void {
                      return;
                    }
                    """.formatted(keyword), keyword);

            assertReservedFailure("""
                    namespace %s;
                    pub routine main() => void {
                      return;
                    }
                    """.formatted(keyword), keyword);

            assertReservedFailure("""
                    import * as %s from './dependency.ores';
                    pub routine main() => void {
                      return;
                    }
                    """.formatted(keyword), keyword);
        }
    }

    @Test
    void constructedAstCannotBypassReservedKeywordInvariant() {
        for (String keyword : RESERVED) {
            Ast.FunctionDecl function = new Ast.FunctionDecl(
                    "f",
                    Ast.CallableKind.FNC,
                    Ast.Visibility.PRIVATE,
                    false,
                    List.of(),
                    List.of(new Ast.Param(Ast.TypeRef.simple("int"), keyword)),
                    Ast.TypeRef.simple("void"),
                    List.of(),
                    List.of(new Ast.ReturnStmt(null)));

            Ast.Program program = new Ast.Program(List.of(
                    new Ast.ModuleDecl("m", List.of(function))));

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(program));

            assertTrue(
                    error.getMessage().contains("reserved keyword '" + keyword + "'"),
                    () -> "AST validator failed to reject '" + keyword + "': " + error.getMessage());
        }
    }

    @Test
    void constructedMemberAndTypeNamesCannotBypassReservation() {
        Ast.FunctionDecl memberUse = new Ast.FunctionDecl(
                "f",
                Ast.CallableKind.FNC,
                Ast.Visibility.PRIVATE,
                false,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                List.of(
                        new Ast.ExprStmt(new Ast.MemberExpr(new Ast.NameExpr("value"), "is")),
                        new Ast.ReturnStmt(null)));

        Ast.Program memberProgram = new Ast.Program(List.of(
                new Ast.ModuleDecl("m", List.of(memberUse))));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(memberProgram));

        Ast.TypeAliasDecl alias = new Ast.TypeAliasDecl(
                "Alias",
                List.of(),
                Ast.TypeRef.simple("as"));
        Ast.Program typeProgram = new Ast.Program(List.of(
                new Ast.ModuleDecl("m", List.of(alias))));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(typeProgram));
    }

    @Test
    void asAndOfRemainValidInTheirGrammarRoles() {
        assertDoesNotThrow(() -> Parser.parse("""
                import * as deps from './dependency.ores';

                pub routine main() => void {
                  for (val item of arr[1, 2, 3]) {
                    stdio.println(item);
                  }
                }
                """));
    }

    @Test
    void reservedWordsCannotBeUsedAsOrdinaryExpressionsOrMembers() {
        for (String keyword : RESERVED) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    pub routine main() => void {
                      stdio.println(%s);
                    }
                    """.formatted(keyword)));

            if (!keyword.equals("stop")) {
                assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                        pub routine main() => void {
                          val value = obj{ok: 1};
                          stdio.println(value.%s);
                        }
                        """.formatted(keyword)));
            }
        }
    }

    @Test
    void stopRemainsAvailableAsContextualActorMemberSelector() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  val worker = actor |String msg| -> {
                    stop;
                  };
                  worker.stop();
                }
                """)));
    }

    private static void assertRejectedEverywhere(Function<String, String> source) {
        for (String keyword : RESERVED) assertReservedFailure(source.apply(keyword), keyword);
    }

    private static void assertReservedFailure(String source, String keyword) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(
                error.getMessage().contains("reserved keyword '" + keyword + "' cannot be used as an identifier"),
                () -> "unexpected error for reserved keyword '" + keyword + "': " + error.getMessage());
    }
}
