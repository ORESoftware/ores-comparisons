package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncSuspensionAnalysisTest {
    @Test
    void spillsOnlyLocalsLiveAcrossAwait() {
        Ast.FunctionDecl target = function("""
                async fnc pause() => int {
                  return 0;
                }

                async fnc target() => int {
    val dead0 = 0;
    val dead1 = 1;
    val dead2 = 2;
    val dead3 = 3;
    val dead4 = 4;
    val dead5 = 5;
    val dead6 = 6;
    val dead7 = 7;
    val dead8 = 8;
    val dead9 = 9;
    val dead10 = 10;
    val dead11 = 11;
    val dead12 = 12;
    val dead13 = 13;
    val dead14 = 14;
    val dead15 = 15;
    val dead16 = 16;
    val dead17 = 17;
    val dead18 = 18;
    val dead19 = 19;
    val dead20 = 20;
    val dead21 = 21;
    val dead22 = 22;
    val dead23 = 23;
    val dead24 = 24;
    val dead25 = 25;
    val dead26 = 26;
    val dead27 = 27;
                  val user = 100;
                  val request = 200;
                  await pause();
                  return user + request;
                }
                """, "target");

        AsyncSuspensionAnalysis.Result result =
                AsyncSuspensionAnalysis.analyze(target);

        assertEquals(Set.of("user", "request"), result.spilledLocalNames());
        assertEquals(2, result.spillSlots().size());
        assertEquals(0, result.temporarySpillSlots());
        assertEquals(1, result.suspensionPoints().size());
        assertEquals(Set.of("user", "request"),
                result.suspensionPoints().getFirst().liveLocals());
    }

    @Test
    void spillSlotsCanBeClearedAsSoonAsTheyAreDeadForNextSuspension() {
        Ast.FunctionDecl target = function("""
                async fnc pause() => int {
                  return 0;
                }

                async fnc target() => int {
                  val first = 1;
                  await pause();
                  val consumed = first;

                  val second = 2;
                  await pause();
                  return second;
                }
                """, "target");

        AsyncSuspensionAnalysis.Result result =
                AsyncSuspensionAnalysis.analyze(target);

        assertEquals(Set.of("first", "second"), result.spilledLocalNames());
        assertEquals(2, result.suspensionPoints().size());

        AsyncSuspensionAnalysis.SuspensionPoint first =
                result.suspensionPoints().get(0);
        AsyncSuspensionAnalysis.SuspensionPoint second =
                result.suspensionPoints().get(1);

        assertEquals(Set.of("first"), first.liveLocals());
        assertEquals(Set.of("second"), second.liveLocals());

        int firstSlot = slot(result, "first");
        int secondSlot = slot(result, "second");

        assertTrue(first.liveSpillSlots().contains(firstSlot));
        assertTrue(first.clearSpillSlots().contains(secondSlot));
        assertTrue(second.liveSpillSlots().contains(secondSlot));
        assertTrue(second.clearSpillSlots().contains(firstSlot));
    }

    @Test
    void countsOnlyAlreadyEvaluatedExpressionTemporariesAcrossAwait() {
        Ast.FunctionDecl target = function("""
                fnc combine(int a, int b, int c) => int {
                  return a + b + c;
                }

                async fnc pause() => int {
                  return 2;
                }

                async fnc target() => int {
                  return combine(1, await pause(), 3);
                }
                """, "target");

        AsyncSuspensionAnalysis.Result result =
                AsyncSuspensionAnalysis.analyze(target);

        assertTrue(result.spillSlots().isEmpty());
        assertEquals(1, result.temporarySpillSlots());
        assertEquals(1,
                result.suspensionPoints().getFirst().temporarySpillCount());
        assertEquals(1, result.totalPersistentSlots());
    }

    @Test
    void assignmentBeforeUseKillsTheOldValueAtEarlierAwait() {
        Ast.FunctionDecl target = function("""
                async fnc pause() => int {
                  return 0;
                }

                async fnc target() => int {
                  let value = 1;
                  await pause();
                  value = 2;
                  return value;
                }
                """, "target");

        AsyncSuspensionAnalysis.Result result =
                AsyncSuspensionAnalysis.analyze(target);

        assertTrue(result.spilledLocalNames().isEmpty());
    }

    @Test
    void branchLivenessUnionsValuesNeededByEitherContinuation() {
        Ast.FunctionDecl target = function("""
                async fnc pause() => int {
                  return 0;
                }

                async fnc target(bool choose) => int {
                  val left = 10;
                  val right = 20;
                  if choose do
                    await pause();
                    return left;
                  else
                    await pause();
                    return right;
                  fi
                }
                """, "target");

        AsyncSuspensionAnalysis.Result result =
                AsyncSuspensionAnalysis.analyze(target);

        assertEquals(Set.of("left", "right"), result.spilledLocalNames());
        assertEquals(2, result.suspensionPoints().size());
        assertEquals(Set.of("left"),
                result.suspensionPoints().get(0).liveLocals());
        assertEquals(Set.of("right"),
                result.suspensionPoints().get(1).liveLocals());
    }

    @Test
    void noAwaitMeansNoPersistentAsyncFrameSlots() {
        Ast.FunctionDecl target = function("""
                async fnc target(int input) => int {
                  val local = input + 1;
                  return local;
                }
                """, "target");

        AsyncSuspensionAnalysis.Result result =
                AsyncSuspensionAnalysis.analyze(target);

        assertTrue(result.spillSlots().isEmpty());
        assertEquals(0, result.temporarySpillSlots());
        assertTrue(result.suspensionPoints().isEmpty());
    }



    @Test
    void shadowedBindingsReceiveDistinctSpillIdentities() {
        Ast.FunctionDecl target = function("""
                async fnc pause() => int {
                  return 0;
                }

                async fnc target() => int {
                  val x = 10;

                  if true do
                    val x = 20;
                    await pause();
                    val inner = x;
                  fi

                  await pause();
                  return x;
                }
                """, "target");

        AsyncSuspensionAnalysis.Result result =
                AsyncSuspensionAnalysis.analyze(target);

        List<AsyncSuspensionAnalysis.SpillSlot> xSlots =
                result.spillSlots().stream()
                        .filter(slot -> slot.name().equals("x"))
                        .toList();

        assertEquals(2, xSlots.size());
        assertNotEquals(
                xSlots.get(0).bindingId(),
                xSlots.get(1).bindingId());

        AsyncSuspensionAnalysis.SuspensionPoint first =
                result.suspensionPoints().get(0);
        AsyncSuspensionAnalysis.SuspensionPoint second =
                result.suspensionPoints().get(1);

        assertEquals(2, first.liveBindingIds().size());
        assertEquals(1, second.liveBindingIds().size());
        assertTrue(second.clearSpillSlots().contains(xSlots.get(1).index()));
    }

    @Test
    void compilerBuildsDenseStateMachinePcsFromAwaitSites() {
        AsyncStateMachinePlan.ProgramPlan program =
                OresCompiler.planAsyncStateMachines("""
                        async fnc pause() => int {
                          return 0;
                        }

                        async fnc target() => int {
                          val retained = 7;
                          await pause();
                          val first = retained;
                          await pause();
                          return first;
                        }
                        """);

        AsyncStateMachinePlan.CallablePlan target =
                program.callables().stream()
                        .filter(plan -> plan.id().callableName().equals("target"))
                        .findFirst()
                        .orElseThrow();

        assertEquals(List.of(0, 1, 2),
                target.states().stream()
                        .map(AsyncStateMachinePlan.State::pc)
                        .toList());
        assertEquals(2, target.frameLayout().suspensionPoints().size());
        assertEquals(Set.of("retained", "first"),
                target.frameLayout().spilledLocalNames());
        assertTrue(target.suspends());
    }

    private static int slot(
            AsyncSuspensionAnalysis.Result result,
            String name) {
        return result.spillSlots().stream()
                .filter(slot -> slot.name().equals(name))
                .findFirst()
                .orElseThrow()
                .index();
    }

    private static Ast.FunctionDecl function(String source, String name) {
        Ast.Program program = OresCompiler.parseAndTypeCheck(source);
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
