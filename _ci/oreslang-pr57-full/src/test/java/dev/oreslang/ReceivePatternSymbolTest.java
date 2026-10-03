package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.PatternSupport;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ReceivePatternSymbolTest {
    @Test
    void parsesAndChecksTypedFifoReceivePatterns() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                pub actor fnc service() => void {
                  receive loop {
                    case [:put, key: String, value: int] => {
                      val String copied_key = key;
                      val int copied_value = value;
                    }
                    case :stop => {
                      return;
                    }
                    default => {
                      val String ignored = "unknown";
                    }
                  }
                  return;
                }
                """));
        assertDoesNotThrow(() -> OwnershipChecker.check(program));

        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ReceivePatternLoopStmt receive =
                assertInstanceOf(Ast.ReceivePatternLoopStmt.class, fn.body().getFirst());
        assertEquals(Ast.ReceiveLoopMode.BLOCKING, receive.mode());
        assertEquals(2, receive.cases().size());

        Ast.ListPattern put = assertInstanceOf(
                Ast.ListPattern.class, receive.cases().getFirst().pattern());
        Ast.LiteralPattern tag = assertInstanceOf(Ast.LiteralPattern.class, put.elements().getFirst());
        assertEquals(new Ast.Symbol("put"), tag.value());

        Ast.TypedBindingPattern key =
                assertInstanceOf(Ast.TypedBindingPattern.class, put.elements().get(1));
        assertEquals("key", key.name());
        assertEquals("String", key.type().name());
    }

    @Test
    void nonblockingPatternLoopUsesTheSamePatternGrammar() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                pub actor fnc drain() => void {
                  try_receive loop {
                    case [:event, value: int] => {
                      val int copied = value;
                    }
                    default => {
                      val bool empty_or_other = true;
                    }
                  }
                  return;
                }
                """));

        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ReceivePatternLoopStmt receive =
                assertInstanceOf(Ast.ReceivePatternLoopStmt.class, fn.body().getFirst());
        assertEquals(Ast.ReceiveLoopMode.NONBLOCKING, receive.mode());
    }

    @Test
    void patternReceiveIsActorOnlyAndRequiresExplicitFallbackForOpenProtocols() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc bad() => void {
                  receive loop {
                    case :ping => {
                      return;
                    }
                    default => {
                      return;
                    }
                  }
                }
                """)));

        IllegalArgumentException missingFallback = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc bad() => void {
                          receive loop {
                            case :ping => {
                              return;
                            }
                          }
                          return;
                        }
                        """)));
        assertTrue(missingFallback.getMessage().contains("never scans past an unmatched FIFO head"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc ok() => void {
                  receive loop {
                    case message => {
                      return;
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void wildcardOrBindingMakesLaterReceiveCasesUnreachable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub actor fnc bad() => void {
                          receive loop {
                            case _ => {
                              return;
                            }
                            case :later => {
                              return;
                            }
                          }
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("unreachable receive case"));
    }

    @Test
    void symbolIsARealCopyTypeRatherThanAStringAlias() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    pub fnc protocol_tag() => Symbol {
                      return :ping;
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        OresSymbol first = OresSymbol.of("ping");
        OresSymbol second = OresSymbol.of("ping");
        assertSame(first, second, "equal symbol names must resolve to one process-wide canonical identity");
        assertEquals(first, second);
        assertNotEquals(first, "ping");
        assertEquals(":ping", first.toString());
    }

    @Test
    void symbolHasStableVerifiedIdentityForTrustedIsolateBridges() {
        OresSymbol ping = OresSymbol.of("ping");
        OresSymbol.SymbolId pingId = OresSymbol.idForName("ping");

        assertEquals(pingId, ping.id());
        assertEquals(64, pingId.hex().length());
        assertEquals(ping.canonicalForm(), new OresSymbol.CanonicalForm(pingId, "ping"));
        assertNotEquals(pingId, OresSymbol.idForName("pong"));

        OresSymbol reconstructed = OresSymbol.fromCanonical(
                ping.canonicalForm(),
                IsolatePolicy.developer());
        assertSame(ping, reconstructed,
                "trusted bridge reconstruction must re-enter the local canonical interner");

        OresSymbol.CanonicalForm forged = new OresSymbol.CanonicalForm(
                new OresSymbol.SymbolId(0L, 0L, 0L, 0L),
                "ping");
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.fromCanonical(forged, IsolatePolicy.developer()));
        assertTrue(mismatch.getMessage().contains("mismatched id"));

        assertThrows(
                SecurityException.class,
                () -> OresSymbol.fromCanonical(
                        ping.canonicalForm(),
                        IsolatePolicy.strictFaas()));
    }

    @Test
    void concurrentTrustedInterningStillProducesOneHeapCanonicalObject() {
        List<OresSymbol> symbols = java.util.stream.IntStream.range(0, 256)
                .parallel()
                .mapToObj(ignored -> OresSymbol.of("concurrent_tag"))
                .toList();

        OresSymbol first = symbols.getFirst();
        assertTrue(symbols.stream().allMatch(symbol -> symbol == first));
        assertEquals(OresSymbol.idForName("concurrent_tag"), first.id());
    }

    @Test
    void symbolEqualityIsNominalAndDoesNotMixWithStrings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc same() => bool {
                  return :ping == :ping;
                }
                """)));

        IllegalArgumentException mixed = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad() => bool {
                          return :ping == "ping";
                        }
                        """)));
        assertTrue(mixed.getMessage().contains("not string aliases"));
    }

    @Test
    void untrustedPoliciesCannotCreateOrReceiveProcessSymbols() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                pub fnc protocol_tag() => Symbol {
                  return :ping;
                }
                """));
        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("symbols"));

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Object>spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    context -> (message, actorContext) -> { });
            SecurityException transportDenied =
                    assertThrows(SecurityException.class, () -> ref.send(OresSymbol.of("ping")));
            assertTrue(transportDenied.getMessage().contains("symbols"));
        }
    }

    @Test
    void purePatternMatcherBindsTypedPayloadsAfterTagMatch() {
        Ast.Program program = Parser.parse("""
                pub actor fnc service() => void {
                  receive loop {
                    case [:put, key: String, value: int] => {
                      return;
                    }
                    default => {
                      return;
                    }
                  }
                  return;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ReceivePatternLoopStmt receive = (Ast.ReceivePatternLoopStmt) fn.body().getFirst();
        Ast.Pattern pattern = receive.cases().getFirst().pattern();

        Map<String, Object> captures = PatternSupport.match(
                pattern,
                List.of(OresSymbol.of("put"), "answer", 42L)).orElseThrow();
        assertEquals("answer", captures.get("key"));
        assertEquals(42L, captures.get("value"));

        assertTrue(PatternSupport.match(
                pattern,
                List.of(OresSymbol.of("get"), "answer", 42L)).isEmpty());
        assertTrue(PatternSupport.match(
                pattern,
                List.of(OresSymbol.of("put"), "answer", "not-an-int")).isEmpty());
    }

    @Test
    void actorTransportTreatsSymbolsAsImmutableSendableScalars() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();

            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> {
                observed.set(message);
                delivered.countDown();
            });

            List<Object> message = List.of(OresSymbol.of("put"), "answer", 42L);
            ref.send(message);

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(message, observed.get());
            assertNotSame(message, observed.get(), "private actor transport must isolate the aggregate");
            List<?> received = assertInstanceOf(List.class, observed.get());
            OresSymbol sent = assertInstanceOf(OresSymbol.class, message.getFirst());
            OresSymbol receivedTag = assertInstanceOf(OresSymbol.class, received.getFirst());
            assertEquals(sent, receivedTag);
            assertEquals(sent.id(), receivedTag.id(),
                    "actor/isolate transport must preserve Symbol identity without requiring shared pointers");
        }
    }

    @Test
    void symbolNamesAreBoundedAndIdentifierShaped() {
        assertEquals(Ast.Symbol.MAX_NAME_LENGTH, OresSymbol.MAX_NAME_LENGTH);
        assertTrue(OresSymbol.internedLimit() > 0);
        assertTrue(OresSymbol.internedLimit() <= OresSymbol.MAX_INTERNED_SYMBOLS);
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.of(""));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.of("not valid"));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.of("a".repeat(OresSymbol.MAX_NAME_LENGTH + 1)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => Symbol {
                  return :%s;
                }
                """.formatted("a".repeat(Ast.Symbol.MAX_NAME_LENGTH + 1))));
    }

    @Test
    void runtimePatternCaptureCanPreserveHostNullWithoutCrashing() {
        Ast.Pattern capture = new Ast.BindingPattern(Ast.BindingKind.VAL, "value");
        Map<String, Object> captures = PatternSupport.match(capture, null).orElseThrow();
        assertTrue(captures.containsKey("value"));
        assertNull(captures.get("value"));
        assertThrows(UnsupportedOperationException.class, () -> captures.put("value", 1L));
    }
}
