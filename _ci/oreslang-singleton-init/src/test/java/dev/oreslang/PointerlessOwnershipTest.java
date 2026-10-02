package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PointerlessOwnershipTest {
    @Test
    void ordinaryParametersBorrowInsteadOfMoving() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                fnc read(Box box) => int {
                  return box.value;
                }

                fnc ok() => void {
                  let Box box = new Box();
                  stdio.println(read(box));
                  stdio.println(box.value);
                  return;
                }
                """)));
    }

    @Test
    void takeParametersMoveOwnership() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc consume(take Box box) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Box box = new Box();
                          consume(box);
                          stdio.println(box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"));
    }

    @Test
    void mutableArgumentsAreExclusiveForTheWholeCall() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;
                        end

                        fnc mixed(mut Box write, Box read) => void {
                          write.value = read.value;
                          return;
                        }

                        fnc bad() => void {
                          let Box box = new Box();
                          mixed(box, box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void pointerBorrowAndDereferenceSyntaxAreRejected() {
        IllegalArgumentException amp = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(&mut String value) => void {
                          return;
                        }
                        """));
        assertTrue(amp.getMessage().contains("pointer-style '&'"));

        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(int value) => int {
                          return *value;
                        }
                        """));
    }

    @Test
    void borrowedParametersCannotMasqueradeAsOwnedReturns() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc identity(Box box) => Box {
                          return box;
                        }
                        """)));

        assertTrue(error.getMessage().contains("return provenance"));
    }

    @Test
    void ownershipSensitiveFunctionsCannotLoseModesWhenExtracted() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc consume(take Box box) => void {
                          return;
                        }

                        fnc bad() => void {
                          val callback = consume;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot be extracted as a first-class Fnc"));
    }

    @Test
    void interfaceImplementationsMustMatchOwnershipModes() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define interface Sink as
                          fnc save(take Box box) => void;
                        end

                        define class Bad is Sink as
                          pub save(Box box) => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("parameter ownership mismatch"));
    }

    @Test
    void inheritedInterfaceOwnershipConflictsAreRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define interface Reader as
                          fnc use(Box box) => void;
                        end

                        define interface Consumer as
                          fnc use(take Box box) => void;
                        end

                        define interface Impossible extends Reader, Consumer as
                        end
                        """)));

        assertTrue(error.getMessage().contains("interface ownership conflict"));
    }

    @Test
    void ownershipIntrinsicsCannotBeShadowed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val int take = 1;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("reserved ownership intrinsic name 'take'"));
    }

    @Test
    void copyIsProvenNotShallowReferenceAliasing() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok() => void {
                  val int x = 7;
                  val int y = copy(x);
                  stdio.println(x);
                  stdio.println(y);
                  return;
                }
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val copyOfBox = copy(box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("not implicitly copyable")
                || error.getMessage().contains("proven Copy"));
    }

    @Test
    void shareDoesNotCreateAmbientSharedMutableState() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          let int value = 1;
                          val shared = share(value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("reserved for explicit shared capabilities"));
    }
}
