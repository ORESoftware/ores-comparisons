package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ComputeContractChecker;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ComputeDataOrientedLanguageTest {
    @Test
    void computeFncCarriesRegionEffectsLayoutsPlacementAndParallelLoopMode() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                compute fnc integrate(Region<Particle> particles, int n): void
                reads particles.position, particles.velocity;
                writes particles.position;
                discards particles.scratch;
                reduces particles.mass by sum;
                atomic particles.counter;
                layout particles soa;
                place auto;
                {
                  parallel simd for int i = 0; i < n; i++ {
                    val next = i + 1;
                  }
                  return;
                }
                """);

        Ast.FunctionDecl fn = rootFunction(program, "integrate");
        assertTrue(ComputeContractChecker.isCompute(fn));

        var contract = ComputeContractChecker.contractOf(fn);
        assertEquals("auto", contract.placement());
        assertEquals(Map.of("particles", "soa"), contract.layouts());
        assertEquals(6, contract.effects().size());
        assertTrue(contract.effects().stream().anyMatch(effect ->
                effect.privilege().equals("reduces")
                        && effect.fieldPath().equals("mass")
                        && "sum".equals(effect.reductionOperator())));

        Ast.ForStmt loop = (Ast.ForStmt) fn.body().getFirst();
        assertEquals(Ast.LoopExecution.PARALLEL_SIMD, loop.execution());
    }

    @Test
    void computeSupportsCpuOrGpuPlacementWithoutChangingLogicalEffects() {
        Ast.Program cpuProgram = OresCompiler.parseAndTypeCheck("""
                compute fnc transform(Region<int> values): void
                reads values;
                writes values;
                place cpu;
                {
                  return;
                }
                """);
        Ast.Program gpuProgram = OresCompiler.parseAndTypeCheck("""
                compute fnc transform(Region<int> values): void
                reads values;
                writes values;
                place gpu;
                {
                  return;
                }
                """);

        assertEquals(
                "cpu",
                ComputeContractChecker.contractOf(rootFunction(cpuProgram, "transform")).placement());
        assertEquals(
                "gpu",
                ComputeContractChecker.contractOf(rootFunction(gpuProgram, "transform")).placement());
    }

    @Test
    void flowChainsAreCompileTimeDeclarationsOverComputeFncs() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                compute fnc integrate(Region<int> values): void
                writes values;
                {
                  return;
                }

                compute fnc collide(Region<int> values): void
                reads values;
                writes values;
                {
                  return;
                }

                compute fnc render(Region<int> values): void
                reads values;
                {
                  return;
                }

                define flow simulation as
                  integrate -> collide -> render;
                end
                """);

        Ast.FlowDecl flow = rootDecl(program, Ast.FlowDecl.class);
        assertEquals("simulation", flow.name());
        assertEquals(
                List.of(
                        new Ast.FlowEdge("integrate", "collide"),
                        new Ast.FlowEdge("collide", "render")),
                flow.edges());
    }

    @Test
    void staticAspectsReferenceOrdinaryHandlersAndPreserveJoinPointMetadata() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                fnc trace(): void {
                  return;
                }

                define aspect Telemetry as
                  on task before trace;
                  on gpu_launch after trace;
                  on await resume after trace;
                end
                """);

        Ast.AspectDecl aspect = rootDecl(program, Ast.AspectDecl.class);
        assertEquals("Telemetry", aspect.name());
        assertEquals(3, aspect.rules().size());
        assertEquals(Ast.AspectJoinPoint.TASK, aspect.rules().get(0).joinPoint());
        assertEquals(Ast.AspectAdviceKind.BEFORE, aspect.rules().get(0).adviceKind());
        assertEquals(Ast.AspectJoinPoint.AWAIT_RESUME, aspect.rules().get(2).joinPoint());
    }

    @Test
    void parallelAndSimdLoopsFailOutsideComputeFnc() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc ordinary(int n): void {
                          parallel for int i = 0; i < n; i++ {
                            val x = i;
                          }
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("only legal inside compute fnc"));
    }

    @Test
    void computeRejectsAmbientIoAwaitAllocationAndArbitraryMemberCalls() {
        assertComputeRejected("""
                compute fnc bad(Region<int> values): void {
                  stdio.println("no");
                  return;
                }
                """);

        assertComputeRejected("""
                compute fnc bad(Region<int> values, Future<int> pending): void {
                  val value = await pending;
                  return;
                }
                """);

        assertComputeRejected("""
                compute fnc bad(Region<int> values): void {
                  val x = new Thing();
                  return;
                }
                """);
    }

    @Test
    void computeBodyMustStayWithinDeclaredRegionEffects() {
        assertComputeRejected("""
                compute fnc bad(Region<Particle> particles, int i): void
                reads particles.position;
                {
                  val x = particles[i].velocity;
                  return;
                }
                """);

        assertComputeRejected("""
                compute fnc bad(Region<Particle> particles, int i): void
                reads particles.position;
                {
                  particles[i].position = 1;
                  return;
                }
                """);

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                compute fnc ok(Region<Particle> particles, int i): void
                writes particles.position;
                {
                  val old = particles[i].position;
                  particles[i].position = old;
                  return;
                }
                """));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                compute fnc overwrite(Region<Particle> particles, int i): void
                discards particles.position;
                {
                  particles[i].position = 1;
                  return;
                }
                """));

        assertComputeRejected("""
                compute fnc badDiscardRead(Region<Particle> particles, int i): void
                discards particles.position;
                {
                  val old = particles[i].position;
                  return;
                }
                """);
    }

    @Test
    void computeRejectsAmbientGlobalsAndShadowedIntrinsicCalls() {
        assertComputeRejected("""
                val int outside = 9;

                compute fnc bad(Region<int> values): int {
                  return outside;
                }
                """);

        assertComputeRejected("""
                compute fnc bad(Region<int> values, int sqrt): int {
                  return sqrt(4);
                }
                """);
    }

    @Test
    void computeRejectsUnboundedLoopsAndRecursiveCallGraphs() {
        assertComputeRejected("""
                compute fnc bad(Region<int> values): void {
                  loop {
                    return;
                  }
                }
                """);

        assertComputeRejected("""
                compute fnc bad(Region<int> values): void {
                  for val i = 0; ; i++ {
                    return;
                  }
                }
                """);

        assertComputeRejected("""
                compute fnc recurse(Region<int> values): void
                reads values;
                {
                  recurse(values);
                  return;
                }
                """);

        assertComputeRejected("""
                compute fnc a(Region<int> values): void
                reads values;
                {
                  b(values);
                  return;
                }

                compute fnc b(Region<int> values): void
                reads values;
                {
                  a(values);
                  return;
                }
                """);
    }

    @Test
    void aspectV0RequiresBeforeAfterZeroArgVoidHandlers() {
        assertComputeRejected("""
                fnc withArg(int x): void { return; }
                define aspect Bad as
                  on task before withArg;
                end
                """);

        assertComputeRejected("""
                fnc returnsValue(): int { return 1; }
                define aspect Bad as
                  on task after returnsValue;
                end
                """);

        IllegalArgumentException around = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc wrap(): void { return; }
                        define aspect Bad as
                          on fnc around wrap;
                        end
                        """));
        assertTrue(around.getMessage().contains("around advice is reserved"));
    }

    @Test
    void computeCallsMustFitCallerEffects() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                compute fnc child(Region<Particle> particles, int i): void
                writes particles.position;
                {
                  particles[i].position = 1;
                  return;
                }

                compute fnc parent(Region<Particle> particles, int i): void
                writes particles.position;
                {
                  child(particles, i);
                  return;
                }
                """));

        assertComputeRejected("""
                compute fnc child(Region<Particle> particles, int i): void
                writes particles.position;
                {
                  particles[i].position = 1;
                  return;
                }

                compute fnc parent(Region<Particle> particles, int i): void
                reads particles.position;
                {
                  child(particles, i);
                  return;
                }
                """);

        assertComputeRejected("""
                compute fnc child(Region<Particle> particles): void
                reduces particles.mass by sum;
                {
                  return;
                }

                compute fnc parent(Region<Particle> particles): void
                reduces particles.mass by max;
                {
                  child(particles);
                  return;
                }
                """);

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                compute fnc child(Region<Particle> particles): void
                reduces particles.mass by sum;
                {
                  return;
                }

                compute fnc parent(Region<Particle> particles): void
                reduces particles.mass by sum;
                {
                  child(particles);
                  return;
                }
                """));
    }

    @Test
    void effectTargetsMustBeRegionParameters() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        compute fnc bad(int value): void
                        writes value;
                        {
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("Region<T> or RegionView<T>"));
    }

    @Test
    void flowCyclesAndNonComputeStagesFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        compute fnc a(Region<int> values): void { return; }
                        compute fnc b(Region<int> values): void { return; }
                        define flow bad as
                          a -> b;
                          b -> a;
                        end
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        compute fnc a(Region<int> values): void { return; }
                        fnc ordinary(): void { return; }
                        define flow bad as
                          a -> ordinary;
                        end
                        """));
    }

    @Test
    void flowCycleDetectionCanonicalizesModuleLocalAndQualifiedStageNames() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module sim
                          compute fnc a(Region<int> values): void { return; }
                          compute fnc b(Region<int> values): void { return; }

                          define flow bad as
                            a -> sim.b;
                            b -> sim.a;
                          end
                        end
                        """));
        assertTrue(failure.getMessage().contains("contains a cycle"));
    }

    @Test
    void flowAndAspectNamesCannotCollideWithRuntimeDeclarations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc pipeline(): void { return; }

                        define flow pipeline as
                        end
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Audit as
                        end

                        define aspect Audit as
                        end
                        """));
    }

    @Test
    void aroundAdviceCannotInterceptComputeDeviceOrSchedulerJoinPoints() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc wrap(): void { return; }
                        define aspect Unsafe as
                          on gpu_launch around wrap;
                        end
                        """));
        assertTrue(failure.getMessage().contains("around advice is reserved"));
    }

    @Test
    void computeAndParallelRemainContextualWordsForOrdinaryMethodsAndNames() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Example as
                  compute(): int { return 1; }
                end

                fnc parallel(): int {
                  return 2;
                }
                """));
    }

    private static void assertComputeRejected(String source) {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    private static Ast.FunctionDecl rootFunction(Ast.Program program, String name) {
        for (Ast.Decl declaration : rootModule(program).declarations()) {
            if (declaration instanceof Ast.FunctionDecl fn && fn.name().equals(name)) return fn;
        }
        fail("missing function " + name);
        return null;
    }

    private static <T extends Ast.Decl> T rootDecl(Ast.Program program, Class<T> type) {
        for (Ast.Decl declaration : rootModule(program).declarations()) {
            if (type.isInstance(declaration)) return type.cast(declaration);
        }
        fail("missing declaration " + type.getSimpleName());
        return null;
    }

    private static Ast.ModuleDecl rootModule(Ast.Program program) {
        return program.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow();
    }
}
