package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCapabilityIsolationHardeningTest {

    @Test
    void privateActorStripsReadonlySharingAsWellAsSharedMemory() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                actor Isolated {
                  pub fnc attempt() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                }
                """));

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(failure.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void privateActorCannotHideSharedMutexBehindTypeAlias() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                type SharedCell = SharedMutex<int>;

                actor Isolated {
                  pub fnc accept(SharedCell cell) => void {
                    return;
                  }
                }
                """));

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(failure.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotHideSharedMutexInsideWrapperClassState() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class SharedBox
                  pub val SharedMutex<int> cell;
                end

                actor Isolated {
                  pub fnc accept(SharedBox box) => void {
                    return;
                  }
                }
                """));

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(failure.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotReachSharedMemoryThroughLocalHelperFunction() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc make_shared() => void {
                  val shared = SharedMutex.new(1);
                  stdio.println(shared.is_poisoned());
                  return;
                }

                actor Isolated {
                  pub fnc attempt() => void {
                    make_shared();
                    return;
                  }
                }
                """));

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(failure.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void sharedActorMayUseExplicitSharingWhenParentPolicyAllowsIt() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                shared actor SharedCounter {
                  pub fnc attempt() => void {
                    val cell = SharedMutex.new(1);
                    val snapshot = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(cell.is_poisoned());
                    stdio.println(snapshot);
                    return;
                  }
                }
                """));

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertThrows(SecurityException.class, () ->
                CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
    }

    @Test
    void checkedInNegativeExampleRemainsTypeValidButCapabilityInvalid() throws Exception {
        String source = Files.readString(
                Path.of("examples/private-actor-sharing-invalid.ores"));

        Ast.Program program = assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse(source)));

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(
                failure.getMessage().contains("SHARED_MEMORY")
                        || failure.getMessage().contains("ACTOR_SHARE_READONLY"));
    }
}
