package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiler-owned async state-machine planning IR.
 *
 * <p>This is the seam between source semantics and executable lowering. The
 * legacy source evaluator may continue to use AsyncPlan as a correctness
 * oracle, while production/JIT/AOT code consumes this plan to emit one
 * resumable frame per async callable.</p>
 *
 * <p>A plan deliberately contains no Java lambdas and no functional
 * continuation graph. Suspension points are numbered PCs and the frame layout
 * is supplied by {@link AsyncSuspensionAnalysis}. A code generator can
 * therefore emit a direct switch(pc) resume loop whose only persistent
 * lexical fields are the reported spill slots.</p>
 */
public final class AsyncStateMachinePlan {
    private AsyncStateMachinePlan() { }

    public enum CallableKind {
        FUNCTION,
        METHOD,
        LAMBDA
    }

    /**
     * Stable-enough compiler identity for source lowering. Lambda ordinals are
     * lexical discovery order within the containing callable.
     */
    public record CallableId(
            String moduleName,
            String ownerName,
            String callableName,
            CallableKind kind,
            int ordinal) {
        public CallableId {
            moduleName = moduleName == null ? "" : moduleName;
            ownerName = ownerName == null ? "" : ownerName;
            Objects.requireNonNull(callableName, "callableName");
            Objects.requireNonNull(kind, "kind");
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
        }

        public String displayName() {
            StringBuilder value = new StringBuilder();
            if (!moduleName.isBlank()) value.append(moduleName).append('.');
            if (!ownerName.isBlank()) value.append(ownerName).append('.');
            value.append(callableName);
            if (kind == CallableKind.LAMBDA) value.append('#').append(ordinal);
            return value.toString();
        }
    }

    /**
     * One state entered after a suspension. pc=0 is always the initial entry
     * state; suspension i resumes at pc=i+1.
     */
    public record State(
            int pc,
            Ast.SourceSite resumeSite,
            List<Integer> liveSpillSlots,
            List<Integer> clearReferenceSlots,
            int temporarySpillCount) {
        public State {
            if (pc < 0) throw new IllegalArgumentException("pc must be non-negative");
            resumeSite = resumeSite == null ? Ast.SourceSite.UNKNOWN : resumeSite;
            liveSpillSlots = List.copyOf(liveSpillSlots);
            clearReferenceSlots = List.copyOf(clearReferenceSlots);
            if (temporarySpillCount < 0) {
                throw new IllegalArgumentException("temporary spill count must be non-negative");
            }
        }
    }

    public record CallablePlan(
            CallableId id,
            AsyncSuspensionAnalysis.Result frameLayout,
            List<State> states) {
        public CallablePlan {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(frameLayout, "frameLayout");
            states = List.copyOf(states);
            if (states.isEmpty() || states.getFirst().pc() != 0) {
                throw new IllegalArgumentException("async state machine must contain pc=0 entry");
            }
            if (states.size() != frameLayout.suspensionPoints().size() + 1) {
                throw new IllegalArgumentException(
                        "state count must equal suspension count + entry");
            }
            for (int i = 0; i < states.size(); i++) {
                if (states.get(i).pc() != i) {
                    throw new IllegalArgumentException(
                            "state PCs must be dense and ordered; expected " + i);
                }
            }
        }

        public boolean suspends() {
            return !frameLayout.suspensionPoints().isEmpty();
        }
    }

    public record ProgramPlan(List<CallablePlan> callables) {
        public ProgramPlan {
            callables = List.copyOf(callables);
        }

        public CallablePlan require(String displayName) {
            return callables.stream()
                    .filter(plan -> plan.id().displayName().equals(displayName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no async callable plan for " + displayName));
        }
    }

    public static ProgramPlan build(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        ArrayList<CallablePlan> plans = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function) {
                    if (function.async()) {
                        plans.add(planFunction(module.name(), function));
                    }
                    collectNestedLambdas(
                            module.name(),
                            "",
                            function.name(),
                            function.body(),
                            plans);
                    continue;
                }

                if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (method.async()) {
                            plans.add(planMethod(
                                    module.name(),
                                    klass.name(),
                                    method));
                        }
                        collectNestedLambdas(
                                module.name(),
                                klass.name(),
                                method.name(),
                                method.body(),
                                plans);
                    }
                }
            }
        }
        return new ProgramPlan(plans);
    }

    private static CallablePlan planFunction(
            String moduleName,
            Ast.FunctionDecl function) {
        return plan(
                new CallableId(
                        moduleName,
                        "",
                        function.name(),
                        CallableKind.FUNCTION,
                        0),
                AsyncSuspensionAnalysis.analyze(function));
    }

    private static CallablePlan planMethod(
            String moduleName,
            String ownerName,
            Ast.MethodDecl method) {
        return plan(
                new CallableId(
                        moduleName,
                        ownerName,
                        method.name(),
                        CallableKind.METHOD,
                        0),
                AsyncSuspensionAnalysis.analyze(method));
    }

    private static CallablePlan planLambda(
            String moduleName,
            String ownerName,
            String containingCallable,
            Ast.LambdaExpr lambda,
            int ordinal) {
        return plan(
                new CallableId(
                        moduleName,
                        ownerName,
                        containingCallable + ".$lambda",
                        CallableKind.LAMBDA,
                        ordinal),
                AsyncSuspensionAnalysis.analyze(lambda));
    }

    private static CallablePlan plan(
            CallableId id,
            AsyncSuspensionAnalysis.Result layout) {
        ArrayList<State> states = new ArrayList<>(
                layout.suspensionPoints().size() + 1);
        states.add(new State(
                0,
                Ast.SourceSite.UNKNOWN,
                List.of(),
                List.of(),
                0));

        for (AsyncSuspensionAnalysis.SuspensionPoint suspension
                : layout.suspensionPoints()) {
            states.add(new State(
                    suspension.pc() + 1,
                    suspension.await().site(),
                    suspension.liveSpillSlots(),
                    suspension.clearSpillSlots(),
                    suspension.temporarySpillCount()));
        }
        return new CallablePlan(id, layout, states);
    }

    private static void collectNestedLambdas(
            String moduleName,
            String ownerName,
            String containingCallable,
            List<Ast.Stmt> statements,
            List<CallablePlan> plans) {
        LambdaCollector collector = new LambdaCollector(
                moduleName,
                ownerName,
                containingCallable,
                plans);
        collector.statements(statements);
    }

    private static final class LambdaCollector {
        private final String moduleName;
        private final String ownerName;
        private final String containingCallable;
        private final List<CallablePlan> plans;
        private int nextOrdinal;

        private LambdaCollector(
                String moduleName,
                String ownerName,
                String containingCallable,
                List<CallablePlan> plans) {
            this.moduleName = moduleName;
            this.ownerName = ownerName;
            this.containingCallable = containingCallable;
            this.plans = plans;
        }

        private void statements(List<Ast.Stmt> statements) {
            for (Ast.Stmt statement : statements) statement(statement);
        }

        private void statement(Ast.Stmt statement) {
            if (statement instanceof Ast.BindingStmt binding) {
                expression(binding.initializer());
                return;
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                expression(destructure.initializer());
                return;
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                expression(returned.value());
                return;
            }
            if (statement instanceof Ast.ExprStmt expr) {
                expression(expr.expression());
                return;
            }
            if (statement instanceof Ast.DeferStmt defer) {
                expression(defer.expression());
                return;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    expression(branch.condition());
                    statements(branch.body());
                }
                statements(conditional.elseBody());
                return;
            }
            if (statement instanceof Ast.TryStmt tried) {
                statements(tried.body());
                statements(tried.catchBody());
                statements(tried.finallyBody());
                return;
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                expression(loop.iterable());
                statements(loop.body());
                return;
            }
            if (statement instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) statement(loop.initializer());
                expression(loop.condition());
                statements(loop.body());
                expression(loop.update());
                return;
            }

            throw new IllegalArgumentException(
                    "unsupported statement while collecting async lambdas: "
                            + statement.getClass().getName());
        }

        private void expression(Ast.Expr expression) {
            if (expression == null) return;

            if (expression instanceof Ast.LambdaExpr lambda) {
                int ordinal = nextOrdinal++;
                if (lambda.async()) {
                    plans.add(planLambda(
                            moduleName,
                            ownerName,
                            containingCallable,
                            lambda,
                            ordinal));
                }

                // Nested lambdas are separate lexical callables. They retain
                // the same outer identity prefix but receive subsequent
                // discovery ordinals.
                if (lambda.blockBody() != null) {
                    statements(lambda.blockBody());
                } else {
                    expression(lambda.expressionBody());
                }
                return;
            }
            if (expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.NameExpr) {
                return;
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                expression(awaited.expression());
                return;
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                expression(unary.operand());
                return;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                expression(binary.left());
                expression(binary.right());
                return;
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                expression(conditional.condition());
                expression(conditional.whenTrue());
                expression(conditional.whenFalse());
                return;
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                expression(assignment.target());
                expression(assignment.value());
                return;
            }
            if (expression instanceof Ast.CallExpr call) {
                expression(call.callee());
                for (Ast.Expr argument : call.arguments()) expression(argument);
                return;
            }
            if (expression instanceof Ast.MemberExpr member) {
                expression(member.receiver());
                return;
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                expression(indexed.receiver());
                expression(indexed.index());
                return;
            }
            if (expression instanceof Ast.NewExpr created) {
                for (Ast.Expr argument : created.arguments()) expression(argument);
                return;
            }
            if (expression instanceof Ast.SpawnExpr spawned) {
                expression(spawned.call());
                return;
            }
            if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) expression(item);
                return;
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) expression(item);
                return;
            }
            if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) expression(field.value());
                return;
            }

            throw new IllegalArgumentException(
                    "unsupported expression while collecting async lambdas: "
                            + expression.getClass().getName());
        }
    }
}
