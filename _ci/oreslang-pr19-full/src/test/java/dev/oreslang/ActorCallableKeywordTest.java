package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCallableKeywordTest {

    @Test
    void actorAndIsoactorAreReservedTokensAndAstModifiers() {
        List<Token> tokens = new Lexer("actor isoactor").scan();
        assertEquals(Token.Type.ACTOR, tokens.get(0).type());
        assertEquals(Token.Type.ISOACTOR, tokens.get(1).type());

        Ast.Program program = Parser.parse("""
                actor fnc shared_worker() => void { return; }
                isoactor routine isolated_worker() => void { return; }
                """);

        List<Ast.FunctionDecl> functions = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .toList();

        assertEquals(2, functions.size());
        assertEquals(Ast.ActorKind.SHARED, functions.get(0).actorKind());
        assertEquals(Ast.ActorKind.ISOLATED, functions.get(1).actorKind());
    }

    @Test
    void decoratedCallablesRunAndKeepTheirDeclaredReturnTypes() throws Exception {
        String output = run("""
                actor fnc add_one(int value) => int {
                  return value + 1;
                }

                isoactor routine emit(String value) => void {
                  stdio.stdout.write(value);
                  return;
                }

                pub routine main() => void {
                  stdio.stdout.write(add_one(41));
                  stdio.stdout.write(":");
                  emit("isolated");
                  return;
                }
                """);

        assertEquals("42:isolated", output);
    }

    @Test
    void mainItselfMayBeAnIsoactorEntrypoint() throws Exception {
        String output = run("""
                pub isoactor routine main() => void {
                  stdio.stdout.write("iso-main");
                  return;
                }
                """);

        assertEquals("iso-main", output);
    }

    @Test
    void actorAndIsoactorCannotBeUsedAsBindingIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main() => void {
                  val int actor = 1;
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main() => void {
                  val int isoactor = 1;
                  return;
                }
                """));
    }

    @Test
    void actorModesCannotBeCombinedRepeatedOrAttachedToNonCallableTargets() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor isoactor fnc invalid() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor actor routine invalid() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                async actor fnc invalid_async_actor() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor type Invalid = int;
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Invalid
                  actor run() => void { return; }
                end
                """));
    }

    @Test
    void typeCheckerTreatsActorCallableLikeItsDeclaredFunctionSignature() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc add_one(int value) => int {
                  return value + 1;
                }

                fnc use_it() => int {
                  return add_one(1) + 40;
                }
                """)));
    }

    @Test
    void synchronousNestedActorCallFromSharedActorIsRejected() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> run("""
                        actor fnc child(int value) => int {
                          return value + 1;
                        }

                        actor fnc parent(int value) => int {
                          return child(value);
                        }

                        pub routine main() => void {
                          stdio.stdout.write(parent(1));
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("shared actor pool"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-keywords.ores")
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
