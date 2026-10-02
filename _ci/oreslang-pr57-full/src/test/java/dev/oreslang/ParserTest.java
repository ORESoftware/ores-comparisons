package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
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
                define module model
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
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertNull(klass.methods().getFirst().explicitReceiverType());
        assertEquals("x", klass.methods().get(1).explicitReceiverType().name());
    }

    @Test
    void lexerRecognizesLambdaAndFatReturnArrows() {
        var tokens = new Lexer("(int x) -> x + 1; fnc f() => int { return 1; }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }
    @Test
    void lexerReservesActorChannelConcurrencyKeywords() {
        var tokens = new Lexer("channel send try_send receive try_receive select case default loop").scan();
        assertEquals(Token.Type.CHANNEL, tokens.get(0).type());
        assertEquals(Token.Type.SEND, tokens.get(1).type());
        assertEquals(Token.Type.TRY_SEND, tokens.get(2).type());
        assertEquals(Token.Type.RECEIVE, tokens.get(3).type());
        assertEquals(Token.Type.TRY_RECEIVE, tokens.get(4).type());
        assertEquals(Token.Type.SELECT, tokens.get(5).type());
        assertEquals(Token.Type.CASE, tokens.get(6).type());
        assertEquals(Token.Type.DEFAULT, tokens.get(7).type());
        assertEquals(Token.Type.LOOP, tokens.get(8).type());
    }

    @Test
    void parsesActorReceiveLoopsChannelsAndSelectAsDedicatedAst() {
        Ast.Program program = Parser.parse("""
                pub actor fnc service() => void {
                  val Channel<int> events = channel<int>(8);

                  receive loop (int msg) {
                    try_send(events, msg);
                  }

                  try_receive loop (int pending) {
                    try_send(events, pending);
                  }

                  select {
                    case receive(events) as int next => {
                      stdio.println(next);
                    }
                    case send(events, 1) => {
                      stdio.println("sent");
                    }
                    default => {
                      stdio.println("idle");
                    }
                  }

                  return;
                }
                """);

        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertInstanceOf(Ast.ChannelExpr.class, ((Ast.BindingStmt) fn.body().get(0)).initializer());

        Ast.ReceiveLoopStmt receiveLoop = assertInstanceOf(Ast.ReceiveLoopStmt.class, fn.body().get(1));
        assertEquals(Ast.ReceiveLoopMode.BLOCKING, receiveLoop.mode());
        assertEquals("msg", receiveLoop.bindingName());
        assertInstanceOf(
                Ast.ChannelOpExpr.class,
                ((Ast.ExprStmt) receiveLoop.body().getFirst()).expression());

        Ast.ReceiveLoopStmt tryReceiveLoop = assertInstanceOf(Ast.ReceiveLoopStmt.class, fn.body().get(2));
        assertEquals(Ast.ReceiveLoopMode.NONBLOCKING, tryReceiveLoop.mode());
        assertEquals("pending", tryReceiveLoop.bindingName());

        Ast.SelectStmt select = assertInstanceOf(Ast.SelectStmt.class, fn.body().get(3));
        assertEquals(2, select.cases().size());
        assertEquals(Ast.ChannelOpKind.RECEIVE, select.cases().get(0).operation().kind());
        assertEquals(Ast.ChannelOpKind.SEND, select.cases().get(1).operation().kind());
        assertFalse(select.defaultBody().isEmpty());

        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void parsesSharedActorAndAllowsMailboxOwnedStateMutation() {
        String source = """
                shared actor Account {
                  let balance = 100;

                  pub fnc withdraw(int amount) => void {
                    self.balance = self.balance - amount;
                    return;
                  }
                }
                """;

        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();

        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
        assertEquals("Account", actor.name());
        assertEquals("withdraw", actor.methods().getFirst().name());

        Ast.Program typed = TypeChecker.check(program);
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void actorFncDefaultsPrivateAndCanBeExplicitlyShared() {
        Ast.Program privateProgram = Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }
                """);
        Ast.FunctionDecl privateActor = (Ast.FunctionDecl) privateProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.PRIVATE, privateActor.actorKind());

        Ast.Program sharedProgram = Parser.parse("""
                pub shared actor fnc worker(int value) => int {
                  return value;
                }
                """);
        Ast.FunctionDecl sharedActor = (Ast.FunctionDecl) sharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, sharedActor.actorKind());
    }

    @Test
    void sharedWithoutActorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared fnc nope() => void {
                  return;
                }
                """));
    }

    @Test
    void actorEntrypointsCannotBeCalledOrConstructedAsOrdinaryValues() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value) => int {
                  return value;
                }

                pub fnc bad() => int {
                  return worker(1);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let int balance = 100;

                  pub fnc read() => int {
                    return self.balance;
                  }
                }

                pub fnc bad() => Account {
                  return new Account();
                }
                """)));
    }

    @Test
    void rejectsDuplicateAndConflictingActorModifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared shared actor Account {
                  let balance = 1;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub private actor fnc worker() => void {
                  return;
                }
                """));
    }

    @Test
    void actorFunctionCannotBeProgramMain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc main() => void {
                  return;
                }
                """)));
    }


    @Test
    void actorSelfAndMutableActorStateCannotEscapeMailboxTurn() {
        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak() => Account {
                        return self;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak() => &mut Account {
                        return &mut self;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak() => &mut Account {
                        val alias = &mut self;
                        return alias;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let Array<int> items = [1, 2, 3];

                      pub fnc leak() => Array<int> {
                        return self.items;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void actorMayReturnCopyLikeStateButCannotPassSelfBorrowToOrdinaryFunction() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc current() => int {
                        return self.balance;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    fnc inspect(&Account account) => int {
                      return 1;
                    }

                    shared actor Account {
                      let balance = 100;

                      pub fnc inspectSelf() => int {
                        return inspect(&self);
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }


    @Test
    void actorInheritanceMustPreserveIsolationKind() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Parent {
                  let value = 1;
                }

                shared actor Child extends Parent {
                  pub fnc current() => int {
                    return self.value;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor Parent {
                  let value = 1;
                }

                shared actor Child extends Parent {
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Child extends Object {
                  let value = 1;
                }
                """)));
    }


}
