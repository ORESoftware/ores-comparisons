package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeOwnershipSyntaxTest {

    @Test
    void rtOwnershipOperationsAcceptCommandAndParenthesizedForms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc copy_command(int value) => int {
                  return rt copy value;
                }

                fnc copy_call(int value) => int {
                  return rt copy(value);
                }
                """)));
    }

    @Test
    void rtOwnershipOperatorsRejectClassAndModuleNamespacesUniformly() {
        for (String operation : java.util.List.of("copy", "take", "borrow", "share")) {
            IllegalArgumentException classError = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define class Box as
                              pub [Symbol.rtCopy]() => self {
                                return new Box();
                              }
                            end

                            fnc bad() => void {
                              val value = rt %s Box;
                              return;
                            }
                            """.formatted(operation))));
            assertTrue(classError.getMessage().contains("class namespace")
                    && classError.getMessage().contains("not an instance"),
                    operation + ": " + classError.getMessage());

            IllegalArgumentException moduleError = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define module values as
                              pub val int answer = 42;
                            end

                            fnc bad() => void {
                              val value = rt %s values;
                              return;
                            }
                            """.formatted(operation))));
            assertTrue(moduleError.getMessage().contains("module namespace")
                    && moduleError.getMessage().contains("not a value"),
                    operation + ": " + moduleError.getMessage());
        }
    }

    @Test
    void copyTakeAndShareOfCopyScalarsLeaveSourceUsable() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let int source = 7;
                  val copied = rt copy source;
                  val taken = rt take source;
                  val shared = rt share source;

                  stdio.stdout.write(source == 7);
                  stdio.stdout.write(":");
                  stdio.stdout.write(copied == 7);
                  stdio.stdout.write(":");
                  stdio.stdout.write(taken == 7);
                  stdio.stdout.write(":");
                  stdio.stdout.write(shared == 7);
                  return;
                }
                """);

        assertTrue(output.equals("true:true:true:true"), output);
    }

    @Test
    void rtCopyRejectsClassNamespaceEvenWhenInstancesAreCopyable() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub [Symbol.rtCopy]() => self {
                            return new Box();
                          }
                        end

                        fnc bad() => void {
                          val copied = rt copy Box;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("class namespace")
                && error.getMessage().contains("not an instance"),
                error.getMessage());
    }

    @Test
    void rtCopyRejectsModuleNamespaceEvenWhenPublicShapeIsCopyable() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module numbers as
                          pub val int answer = 42;
                        end

                        fnc bad() => void {
                          val copied = rt copy numbers;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("module namespace")
                && error.getMessage().contains("not a value"),
                error.getMessage());
    }

    @Test
    void rtCopyOfPrimitiveValuesIsAllocationFreeValueSemantics() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let int number = 41;
                  val copiedNumber = rt copy number;

                  let bool flag = true;
                  val copiedFlag = rt copy flag;

                  let String text = "ores";
                  val copiedText = rt copy text;

                  stdio.stdout.write(number == 41);
                  stdio.stdout.write(":");
                  stdio.stdout.write(copiedNumber == 41);
                  stdio.stdout.write(":");
                  stdio.stdout.write(flag == true);
                  stdio.stdout.write(":");
                  stdio.stdout.write(copiedFlag == true);
                  stdio.stdout.write(":");
                  stdio.stdout.write(text == "ores");
                  stdio.stdout.write(":");
                  stdio.stdout.write(copiedText == "ores");
                  return;
                }
                """);

        assertTrue(output.equals("true:true:true:true:true:true"), output);
    }

    @Test
    void ordinaryClassArgumentPassesSameReferenceWithoutMovingCaller() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc read_name(Person person) => String {
                  return person.name;
                }

                fnc ok() => void {
                  let Person p = new Person();
                  stdio.println(read_name(p));
                  stdio.println(read_name(p));
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void ordinaryReferenceParameterCannotMutateTheCallerObject() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub let String name = "Alex";
                        end

                        fnc rename(Person person) => void {
                          person.name = "Taylor";
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("mut")
                || error.getMessage().toLowerCase().contains("borrow")
                || error.getMessage().toLowerCase().contains("mutable"),
                error.getMessage());
    }

    @Test
    void mutableParameterMutatesTheSameReferencedObject() throws Exception {
        String output = run("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                pub routine main() => void {
                  let Person p = new Person();
                  rename(p);
                  stdio.stdout.write(p.name);
                  return;
                }
                """);

        assertTrue(output.equals("Taylor"), output);
    }

    @Test
    void typeMutNameIsCanonicalMutableReferenceSyntax() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                fnc ok() => void {
                  let Person p = new Person();
                  rename(p);
                  rename(p);
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void pipeLambdaAcceptsCanonicalTypeMutNameSyntax() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc make() => void {
                  val rename = |Person mut person| -> {
                    person.name = "Taylor";
                    return;
                  };
                  return;
                }
                """));
    }

    @Test
    void pipeLambdaRejectsDuplicateMutMarkers() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Person as
                        end

                        fnc bad() => void {
                          val f = |mut Person mut person| -> {
                            return;
                          };
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("duplicate mut"), error.getMessage());
    }

    @Test
    void rtBorrowUsesSameReferenceAndBlocksOverlappingMutation() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub let String name = "Alex";
                        end

                        fnc rename(Person mut person) => void {
                          person.name = "Taylor";
                          return;
                        }

                        fnc bad() => void {
                          let Person p = new Person();
                          val view = rt borrow p;
                          rename(p);
                          stdio.println(view.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }

    @Test
    void rtTakeTransfersOwnershipAndRejectsLaterCallerUse() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          let Person owned = rt take p;
                          stdio.println(owned.name);
                          stdio.println(p.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"), error.getMessage());
    }

    @Test
    void pointerStyleBorrowSyntaxRemainsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Person as
                        end

                        fnc bad(&Person person) => void {
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("pointer-style '&'"), error.getMessage());
    }

    @Test
    void rtCopyUsesExplicitClassCopyContractAndProducesIndependentIdentity() throws Exception {
        String output = run("""
                define class Person as
                  pub let String name = "Alex";

                  pub [Symbol.rtCopy]() => self {
                    return new Person();
                  }
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                pub routine main() => void {
                  let Person original = new Person();
                  let Person copied = rt copy original;
                  rename(copied);
                  stdio.stdout.write(original.name);
                  stdio.stdout.write(":");
                  stdio.stdout.write(copied.name);
                  return;
                }
                """);

        assertTrue(output.equals("Alex:Taylor"), output);
    }

    @Test
    void rtCopyUsesWellKnownSymbolAndLeavesOrdinaryCopyNameFree() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 1;

                  pub copy() => self {
                    return new Box(99);
                  }

                  pub [Symbol.rtCopy]() => self {
                    return new Box(self.value);
                  }
                end

                pub routine main() => void {
                  let Box original = new Box(7);
                  let Box copied = rt copy original;
                  let Box ordinary = original.copy();
                  stdio.stdout.write(copied.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(ordinary.value);
                  return;
                }
                """);

        assertTrue(output.equals("7:99"), output);
    }

    @Test
    void ordinaryCopyMethodDoesNotSatisfyRtCopyContract() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub copy() => self {
                            return new Box();
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val copied = rt copy box;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("Symbol.rtCopy"), error.getMessage());
    }

    @Test
    void rtCopyRecursesThroughStructuralRecordFields() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 7;

                  pub [Symbol.rtCopy]() => self {
                    return new Box(self.value);
                  }
                end

                pub routine main() => void {
                  let original = obj{child: new Box(7)};
                  let copied = rt copy original;
                  copied.child.value = 9;
                  stdio.stdout.write(original.child.value);
                  stdio.stdout.write(copied.child.value);
                  return;
                }
                """);

        assertTrue(output.equals("79"), output);
    }

    @Test
    void rtCopyContractRejectsMutableReceiverAndReturningSelf() {
        IllegalArgumentException mutableReceiver = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;

                          pub [Symbol.rtCopy](mut self)() => self {
                            self.value = self.value + 1;
                            return new Box();
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val copied = rt copy box;
                          return;
                        }
                        """)));
        String mutableMessage = mutableReceiver.getMessage().toLowerCase();
        assertTrue(mutableMessage.contains("copy")
                && (mutableMessage.contains("read-only") || mutableMessage.contains("immutable")),
                mutableReceiver.getMessage());

        IllegalArgumentException returnsSelf = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 1;

                          pub [Symbol.rtCopy]() => self {
                            return self;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val copied = rt copy box;
                          return;
                        }
                        """)));
        String returnMessage = returnsSelf.getMessage().toLowerCase();
        assertTrue(returnMessage.contains("borrow")
                || returnMessage.contains("return ownership")
                || returnMessage.contains("copy"),
                returnsSelf.getMessage());
    }

    @Test
    void rtCopyRejectsClassGraphsWithHiddenCallableState() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Holder as
                          pub val (() -> int) callback = || -> {
                            return 1;
                          };

                          pub [Symbol.rtCopy]() => self {
                            return new Holder();
                          }
                        end

                        fnc bad() => void {
                          let Holder holder = new Holder();
                          val copied = rt copy holder;
                          return;
                        }
                        """)));

        String message = error.getMessage().toLowerCase();
        assertTrue(message.contains("copy")
                && (message.contains("callable") || message.contains("fnc")),
                error.getMessage());
    }

    @Test
    void rtCopyRejectsIdentityClassWithoutCopyContract() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          let Person q = rt copy p;
                          stdio.println(q.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("copy"), error.getMessage());
    }

    @Test
    void rtShareDoesNotRequireCopyContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc ok() => void {
                  let Person p = new Person();
                  val shared = rt share p;
                  stdio.println(shared.name);
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void rtSharePreservesIdentityWhileRtCopyCreatesNewIdentity() throws Exception {
        String output = run("""
                define class Person as
                  pub val String name = "Alex";

                  pub [Symbol.rtCopy]() => self {
                    return new Person();
                  }
                end

                pub routine main() => void {
                  let Person p = new Person();
                  val shared = rt share p;
                  val copied = rt copy shared;
                  stdio.stdout.write(shared == p);
                  stdio.stdout.write(":");
                  stdio.stdout.write(copied == p);
                  return;
                }
                """);

        assertTrue(output.equals("true:false"), output);
    }

    @Test
    void rtShareRemovesUniqueMutationAuthorityFromOriginalOwner() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub let String name = "Alex";
                        end

                        fnc rename(Person mut person) => void {
                          person.name = "Taylor";
                          return;
                        }

                        fnc bad() => void {
                          let Person p = new Person();
                          val shared = rt share p;
                          stdio.println(shared.name);
                          rename(p);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("shared")
                || error.getMessage().toLowerCase().contains("ownership"), error.getMessage());
    }

    @Test
    void rtShareAliasCannotBeMutatedOrTaken() {
        IllegalArgumentException mutate = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub let String name = "Alex";
                        end

                        fnc rename(Person mut person) => void {
                          person.name = "Taylor";
                          return;
                        }

                        fnc bad() => void {
                          let Person p = new Person();
                          val shared = rt share p;
                          rename(shared);
                          return;
                        }
                        """)));
        assertTrue(mutate.getMessage().toLowerCase().contains("shared")
                || mutate.getMessage().toLowerCase().contains("mut"), mutate.getMessage());

        IllegalArgumentException take = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          val shared = rt share p;
                          val owned = rt take shared;
                          return;
                        }
                        """)));
        assertTrue(take.getMessage().toLowerCase().contains("shared")
                || take.getMessage().toLowerCase().contains("ownership"), take.getMessage());
    }

    @Test
    void sharedOwnerBindingCanRebindToFreshUniqueValue() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                fnc ok() => void {
                  let Person p = new Person();
                  val shared = rt share p;
                  p = new Person();
                  rename(p);
                  stdio.println(shared.name);
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void mutParameterBindingCannotBeReassigned() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad(Person mut person) => void {
                          person = new Person();
                          return;
                        }
                        """)));

        String message = error.getMessage().toLowerCase();
        assertTrue((message.contains("rebind") || message.contains("reassign"))
                && message.contains("person"),
                error.getMessage());
    }

    @Test
    void rtShareCanBeDuplicatedAsSharedOwnership() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc ok() => void {
                  let Person p = new Person();
                  val first = rt share p;
                  val second = rt share p;
                  stdio.println(first.name);
                  stdio.println(second.name);
                  return;
                }
                """)));
    }

    @Test
    void sharedOwnershipCannotBeLaunderedThroughReturnOrAggregate() {
        IllegalArgumentException returned = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => Person {
                          let Person p = new Person();
                          return rt share p;
                        }
                        """)));
        assertTrue(returned.getMessage().contains("Shared<T>")
                || returned.getMessage().toLowerCase().contains("shared ownership"),
                returned.getMessage());

        IllegalArgumentException stored = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          val shared = rt share p;
                          val wrapped = obj{person: shared};
                          return;
                        }
                        """)));
        assertTrue(stored.getMessage().contains("Shared<T>")
                || stored.getMessage().toLowerCase().contains("shared ownership"),
                stored.getMessage());
    }

    @Test
    void sharedArrayRemainsReadableAndIterable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok() => void {
                  let Array<int> values = arr[1, 2, 3];
                  val shared = rt share values;
                  stdio.println(shared[0]);
                  for value of shared do
                    stdio.println(value);
                  done
                  return;
                }
                """)));
    }

    @Test
    void borrowingASharedOwnerRemainsNonOwning() {
        IllegalArgumentException returnedBorrow = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => Person {
                          let Person p = new Person();
                          val shared = rt share p;
                          return rt borrow shared;
                        }
                        """)));

        String message = returnedBorrow.getMessage().toLowerCase();
        assertTrue(message.contains("borrow") && message.contains("return"),
                returnedBorrow.getMessage());
    }

    @Test
    void rtShareRejectsCallableValuesUntilEffectsAreModeled() {
        IllegalArgumentException direct = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val (() -> int) counter = || -> {
                            return 1;
                          };
                          val shared = rt share counter;
                          return;
                        }
                        """)));
        String directMessage = direct.getMessage().toLowerCase();
        assertTrue(directMessage.contains("share")
                && (directMessage.contains("fnc") || directMessage.contains("callable")),
                direct.getMessage());

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          val (() -> int) counter = || -> {
                            return 1;
                          };
                          let Array<(() -> int)> callbacks = arr[counter];
                          val shared = rt share callbacks;
                          return;
                        }
                        """)));
        String nestedMessage = nested.getMessage().toLowerCase();
        assertTrue(nestedMessage.contains("share")
                && (nestedMessage.contains("fnc") || nestedMessage.contains("callable")),
                nested.getMessage());
    }

    @Test
    void rtShareRejectsUnlinkedForeignTypes() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import class External from "../../foreign";

                        fnc bad(External value) => void {
                          val shared = rt share value;
                          return;
                        }
                        """)));

        String message = error.getMessage().toLowerCase();
        assertTrue(message.contains("share")
                && (message.contains("linked") || message.contains("contract")),
                error.getMessage());
    }

    @Test
    void rtParenthesesCanGroupWiderExpression() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc grouped(int left, int right) => int {
                  return rt copy(left + right);
                }

                fnc unary_precedence(int left, int right) => int {
                  return rt copy left + right;
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rt-ownership.ores")
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
