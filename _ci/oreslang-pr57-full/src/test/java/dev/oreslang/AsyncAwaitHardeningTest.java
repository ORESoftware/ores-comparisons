package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncAwaitHardeningTest {
    @Test
    void asyncAndAwaitAreReservedAndDuplicateAsyncIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main() => void {
                  val int async = 1;
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main() => void {
                  val int await = 1;
                  return;
                }
                """));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub async async fnc bad() => int {
                  return 1;
                }
                """));
        assertTrue(duplicate.getMessage().contains("duplicate 'async' modifier"));
    }

    @Test
    void programMainMustRemainSynchronous() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub async routine main() => void {
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("main"));
        assertTrue(error.getMessage().contains("cannot be async"));
    }

    @Test
    void asyncCallableReturnsFutureOfDeclaredResultAndAwaitUnwrapsIt() {
        Ast.Program program = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub async fnc fetch() => int {
                  return 42;
                }

                pub async fnc use() => int {
                  return await fetch();
                }

                pub fnc start() => void {
                  val Future<int> pending = fetch();
                  return;
                }
                """)));

        Ast.FunctionDecl fetch = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(fetch.async());
        assertEquals("int", fetch.returnType().name(), "AST stores the fulfilled result type, not Future<int>");
    }

    @Test
    void asyncAndActorAreMutuallyExclusive() {
        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub async actor fnc worker() => int {
                  return 1;
                }
                """));
        assertTrue(direct.getMessage().contains("mutually exclusive"));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared actor Worker {
                  pub async fnc run() => int {
                    return 1;
                  }
                }
                """));
    }

    @Test
    void actorIsReservedAndUnqualifiedActorMeansPrivate() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main() => void {
                  val int actor = 1;
                  return;
                }
                """));

        Ast.Program program = Parser.parse("""
                pub actor fnc private_worker() => int {
                  return 1;
                }

                pub shared actor fnc shared_worker() => int {
                  return 2;
                }
                """);

        var functions = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .toList();

        assertEquals(Ast.ActorKind.PRIVATE, functions.get(0).actorKind());
        assertEquals(Ast.ActorKind.SHARED, functions.get(1).actorKind());
    }

    @Test
    void actorCallReturnsHandleRatherThanFuture() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub async fnc collect() => int {
                  val ActorHandle<int> handle = worker(7);
                  val ActorId actor_id = handle.id;
                  val ActorKind kind = handle.kind;
                  val ActorStatus status = handle.status();
                  val bool alive = handle.is_alive();
                  return await handle.result();
                }
                """)));
    }

    @Test
    void moduleQualifiedActorCallReturnsHandle() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module workers
                  pub actor fnc worker(int value) => int {
                    return value;
                  }
                end

                define module app
                  pub fnc launch() => void {
                    val ActorHandle<int> handle = workers.worker(1);
                    return;
                  }
                end
                """)));
    }

    @Test
    void actorHandleItselfIsNotAwaitable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker() => int {
                  return 1;
                }

                pub async fnc bad() => int {
                  return await worker();
                }
                """)));
        assertTrue(error.getMessage().contains("ActorHandle"));
        assertTrue(error.getMessage().contains("not awaitable"));
    }

    @Test
    void awaitRequiresAsyncOrActorLexicalContext() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc bad(Future<int> pending) => int {
                  return await pending;
                }
                """)));
        assertTrue(error.getMessage().contains("only legal inside an async callable or an actor mailbox turn"));
    }

    @Test
    void awaitRequiresFutureEvenInsideAsyncCallable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub async fnc bad() => int {
                  return await 42;
                }
                """)));
        assertTrue(error.getMessage().contains("await requires Future"));
    }

    @Test
    void normalLambdaDoesNotInheritAsyncPermission() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub async fnc bad(Future<int> pending) => void {
                  val Fnc<Future<int>, int> callback = (Future<int> value) -> {
                    return await value;
                  };
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("only legal inside an async callable or an actor mailbox turn"));
    }

    @Test
    void ordinaryBorrowCannotCrossAwaitSuspension() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub async fnc fetch() => int {
                  return 1;
                }

                pub async fnc bad(List<int> values) => int {
                  val view = &values;
                  return await fetch();
                }
                """)));
        assertTrue(error.getMessage().contains("live across await"));
    }

    @Test
    void asyncReturnAnnotationNamesFulfilledValueNotNestedFuture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub async fnc bad() => Future<int> {
                  return 1;
                }
                """)));
        assertTrue(error.getMessage().contains("fulfilled result type"));
    }

    @Test
    void actorReturnAnnotationNamesResultNotFuture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc bad() => Future<int> {
                  return 1;
                }
                """)));
        assertTrue(error.getMessage().contains("ActorHandle"));
    }

    @Test
    void asyncIsPartOfInterfaceContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface Fetcher {
                  async fnc fetch() => int;
                }

                define class Good implements Fetcher
                  pub async fetch() => int {
                    return 1;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define interface Fetcher {
                  async fnc fetch() => int;
                }

                define class Bad implements Fetcher
                  pub fetch() => int {
                    return 1;
                  }
                end
                """)));
        assertTrue(error.getMessage().contains("does not implement interface"));
    }
}
