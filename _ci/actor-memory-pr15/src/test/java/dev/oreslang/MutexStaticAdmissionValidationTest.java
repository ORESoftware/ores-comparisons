package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MutexStaticAdmissionValidationTest {
    @Test
    void sharedMutexFactoryRequiresCapability() {
        var program = Parser.parse("""
                define module app
                  fnc main() => void {
                    val shared = SharedMutex.new(arr[1, 2, 3]);
                    return;
                  }
                end
                """);

        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void sharedMutexSignatureRequiresCapability() {
        var program = Parser.parse("""
                define module app
                  fnc pass(SharedMutex<int> value) => void {
                    return;
                  }
                end
                """);

        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void sharedMutexAliasRequiresCapability() {
        var program = Parser.parse("""
                define module app
                  type SharedCounter = SharedMutex<int>;
                  fnc main() => void {
                    return;
                  }
                end
                """);

        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
    }

    @Test
    void bareSharedMutexNamespaceRequiresCapability() {
        var program = Parser.parse("""
                define module app
                  fnc main() => void {
                    val factory = SharedMutex;
                    return;
                  }
                end
                """);

        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void sharedSafeRejectsActorLocalMutexGraph() {
        var program = Parser.parse("""
                define module app
                  fnc bad() => void {
                    val local = Mutex.new(arr[1, 2, 3]);
                    val shared = SharedMutex.new(local);
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("shared-safe owned data"));
    }

    @Test
    void ownershipRejectsAwaitWithLiveGuard() {
        var program = Parser.parse("""
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad() => void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    await mutex.lock_async();
                    guard.value = 1;
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("cannot await while holding a MutexGuard"));
    }

    @Test
    void ownershipRejectsGuardEscapeThroughGenericConstructor() {
        var program = Parser.parse("""
                define module model
                  define class Box<T>
                    pub let T value;
                  end

                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad() => void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val hidden = new Box<>(guard);
                    stdio.println(hidden);
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains(
                "MutexGuard cannot be stored in a constructed object"));
    }


    @Test
    void ownershipRejectsBorrowedGuardEscapeThroughGenericConstructor() {
        var program = Parser.parse("""
                define module model
                  define class Box<T>
                    pub let T value;
                  end

                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad() => void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val hidden = new Box<>(&guard);
                    stdio.println(hidden);
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains(
                "MutexGuard cannot be stored in a constructed object"));
    }

    @Test
    void mutexFactoriesRejectBorrowedPayloads() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad() => void {
                            val data = arr[1, 2, 3];
                            val mutex = Mutex.new(&data);
                            stdio.println(mutex);
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("requires owned data"));
    }

    @Test
    void sharedSafeUnionsRequireEveryArmToBeSafe() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc good(SharedMutex<int | string> value) => void {
                    return;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad(SharedMutex<int | Mutex<int>> value) => void {
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("concrete shared-safe type"));
    }


    @Test
    void conditionalExpressionsPreserveGuardLinearity() {
        var program = Parser.parse("""
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad(bool choose) => void {
                    val mutex = Mutex.new(new Counter());
                    val maybe_guard = choose ? 1 : mutex.lock();
                    await mutex.lock_async();
                    stdio.println(maybe_guard);
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains(
                "cannot await while holding a MutexGuard"));
    }

}
