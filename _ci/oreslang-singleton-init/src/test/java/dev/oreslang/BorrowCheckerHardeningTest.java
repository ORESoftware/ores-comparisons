package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BorrowCheckerHardeningTest {
    @Test
    void projectedReadArgumentReservesRootForWholeCall() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Inner as
                          pub val int value = 7;
                        end

                        define class Outer as
                          pub val Inner inner = new Inner();
                        end

                        fnc mixed(Inner read, mut Outer write) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Outer outer = new Outer();
                          mixed(outer.inner, outer);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void borrowCannotBeLaunderedThroughOwnedAggregate() {
        IllegalArgumentException array = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val hidden = arr[borrow(box)];
                          return;
                        }
                        """)));
        assertTrue(array.getMessage().contains("cannot store a borrowed value"));

        IllegalArgumentException object = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val hidden = obj{child: borrow(box)};
                          return;
                        }
                        """)));
        assertTrue(object.getMessage().contains("cannot store a borrowed value"));
    }

    @Test
    void borrowCannotBeLaunderedThroughAssignment() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box first = new Box();
                          let Box second = new Box();
                          first = borrow(second);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot store a borrowed value"));
    }

    @Test
    void nestedClosureCapturePropagatesToEveryEscapingAncestor() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();

                          val outer = || -> {
                            val inner = || -> {
                              stdio.println(box.value);
                              return;
                            };
                            return;
                          };

                          stdio.println(box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"));
    }

    @Test
    void ordinaryForOfDoesNotManufactureElementOwnership() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc consume(take Box box) => void {
                          return;
                        }

                        fnc bad(Array<Box> boxes) => void {
                          for (val box of boxes) {
                            consume(box);
                          }
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("requires ownership")
                || error.getMessage().contains("borrowed"));
    }

    @Test
    void takeIterableTransfersElementOwnership() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub val int value = 7;
                end

                fnc consume(take Box box) => void {
                  return;
                }

                fnc ok() => void {
                  let boxes = arr[new Box()];
                  for (val box of take(boxes)) {
                    consume(box);
                  }
                  return;
                }
                """)));
    }

    @Test
    void deferRetainsReadBorrowUntilBlockExit() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc read(Box box) => void {
                          stdio.println(box.value);
                          return;
                        }

                        fnc consume(take Box box) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Box box = new Box();
                          defer read(box);
                          consume(box);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void borrowedConstructorFieldIsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        define class Holder as
                          pub val Box child;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val Holder holder = new Holder(borrow(box));
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot store a borrowed value"));
    }
}
