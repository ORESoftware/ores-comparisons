package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public static Ast.Program parseAndTypeCheck(String source) {
        return TypeChecker.check(Parser.parse(source));
    }

    /**
     * Parse/type-check source and build the compiler-owned async state-machine
     * plan used by production lowering. This is intentionally separate from
     * the legacy AsyncPlan source evaluator.
     */
    public static AsyncStateMachinePlan.ProgramPlan planAsyncStateMachines(
            String source) {
        return AsyncStateMachinePlan.build(parseAndTypeCheck(source));
    }

    /**
     * Build async state-machine plans for an already checked program.
     */
    public static AsyncStateMachinePlan.ProgramPlan planAsyncStateMachines(
            Ast.Program program) {
        return AsyncStateMachinePlan.build(
                java.util.Objects.requireNonNull(program, "program"));
    }

    /**
     * Performs syntax, type, and language-capability admission without
     * executing guest code.
     */
    public static Ast.Program validateForIsolate(String source, IsolatePolicy policy) {
        Ast.Program program = parseAndTypeCheck(source);
        CapabilityChecker.check(program, policy);
        return program;
    }
}
