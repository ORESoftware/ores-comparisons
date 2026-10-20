package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Suspension-aware liveness analysis for compiler-lowered async callables.
 *
 * <p>The source evaluator currently builds functional AsyncPlan graphs. This
 * pass is independent of that evaluator: it computes the compact persistent
 * frame layout required by a production pc/switch state-machine lowering.
 * Only values live across a suspension become frame slots.</p>
 *
 * <p>Bindings are resolved to lexical identities before liveness is computed.
 * This is required for correctness: an outer and inner binding with the same
 * source name can both be live across the same await and therefore require
 * distinct spill slots.</p>
 */
public final class AsyncSuspensionAnalysis {
    private AsyncSuspensionAnalysis() { }

    public record SpillSlot(
            int index,
            int bindingId,
            String name,
            Ast.TypeRef declaredType,
            boolean referenceLike) {
        public SpillSlot {
            if (index < 0) throw new IllegalArgumentException("spill slot index must be non-negative");
            if (bindingId < 0) throw new IllegalArgumentException("binding id must be non-negative");
            Objects.requireNonNull(name, "name");
        }
    }

    public record SuspensionPoint(
            int pc,
            Ast.AwaitExpr await,
            List<Integer> liveBindingIds,
            Set<String> liveLocals,
            List<Integer> liveSpillSlots,
            List<Integer> clearSpillSlots,
            int temporarySpillCount) {
        public SuspensionPoint {
            if (pc < 0) throw new IllegalArgumentException("pc must be non-negative");
            Objects.requireNonNull(await, "await");
            liveBindingIds = List.copyOf(liveBindingIds);
            liveLocals = Set.copyOf(liveLocals);
            liveSpillSlots = List.copyOf(liveSpillSlots);
            clearSpillSlots = List.copyOf(clearSpillSlots);
            if (temporarySpillCount < 0) {
                throw new IllegalArgumentException("temporary spill count must be non-negative");
            }
        }
    }

    public record Result(
            List<SpillSlot> spillSlots,
            int temporarySpillSlots,
            List<SuspensionPoint> suspensionPoints) {
        public Result {
            spillSlots = List.copyOf(spillSlots);
            suspensionPoints = List.copyOf(suspensionPoints);
            if (temporarySpillSlots < 0) {
                throw new IllegalArgumentException("temporary spill count must be non-negative");
            }
        }

        public int totalPersistentSlots() {
            return spillSlots.size() + temporarySpillSlots;
        }

        /** Human-readable only: distinct shadowed bindings may share a name. */
        public Set<String> spilledLocalNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (SpillSlot slot : spillSlots) names.add(slot.name());
            return Set.copyOf(names);
        }

        public List<Integer> spilledBindingIds() {
            return spillSlots.stream().map(SpillSlot::bindingId).toList();
        }
    }

    public static Result analyze(Ast.FunctionDecl function) {
        Objects.requireNonNull(function, "function");
        Analyzer analyzer = new Analyzer();
        Scope parameters = new Scope(null);
        for (Ast.Param parameter : function.parameters()) {
            analyzer.declare(parameters, parameter.name(), parameter.type());
        }
        analyzer.resolveBlock(function.body(), parameters);
        analyzer.collectSuspensions(function.body());
        analyzer.liveBlock(function.body(), Set.of());
        return analyzer.finish();
    }

    public static Result analyze(Ast.MethodDecl method) {
        Objects.requireNonNull(method, "method");
        Analyzer analyzer = new Analyzer();
        Scope parameters = new Scope(null);
        if (!method.isStatic()) analyzer.declare(parameters, "self", null);
        for (Ast.Param parameter : method.parameters()) {
            analyzer.declare(parameters, parameter.name(), parameter.type());
        }
        analyzer.resolveBlock(method.body(), parameters);
        analyzer.collectSuspensions(method.body());
        analyzer.liveBlock(method.body(), Set.of());
        return analyzer.finish();
    }

    public static Result analyze(Ast.LambdaExpr lambda) {
        Objects.requireNonNull(lambda, "lambda");
        Analyzer analyzer = new Analyzer();
        Scope parameters = new Scope(null);
        for (Ast.Param parameter : lambda.parameters()) {
            analyzer.declare(parameters, parameter.name(), parameter.type());
        }
        if (lambda.blockBody() != null) {
            analyzer.resolveBlock(lambda.blockBody(), parameters);
            analyzer.collectSuspensions(lambda.blockBody());
            analyzer.liveBlock(lambda.blockBody(), Set.of());
        } else if (lambda.expressionBody() != null) {
            analyzer.resolveExpression(lambda.expressionBody(), parameters);
            analyzer.collectSuspensions(lambda.expressionBody(), 0);
            analyzer.liveExpression(lambda.expressionBody(), Set.of());
        }
        return analyzer.finish();
    }

    private record Binding(int id, String name, Ast.TypeRef type) {
        private Binding {
            if (id < 0) throw new IllegalArgumentException("negative binding id");
            Objects.requireNonNull(name, "name");
        }
    }

    private static final class Scope {
        private final Scope parent;
        private final Map<String, Binding> bindings = new LinkedHashMap<>();

        private Scope(Scope parent) {
            this.parent = parent;
        }

        private void define(Binding binding) {
            if (bindings.putIfAbsent(binding.name(), binding) != null) {
                throw new IllegalArgumentException("duplicate lexical binding " + binding.name());
            }
        }

        private Binding lookup(String name) {
            Binding local = bindings.get(name);
            return local != null ? local : parent == null ? null : parent.lookup(name);
        }
    }

    private static final class Analyzer {
        private static final int MAX_FIXED_POINT_ITERATIONS = 256;

        private final ArrayList<Binding> bindings = new ArrayList<>();
        private final IdentityHashMap<Ast.BindingStmt, Binding> bindingStatements =
                new IdentityHashMap<>();
        private final IdentityHashMap<Ast.DestructureBinding, Binding> destructureBindings =
                new IdentityHashMap<>();
        private final IdentityHashMap<Ast.ForOfStmt, Binding> forOfBindings =
                new IdentityHashMap<>();
        private final IdentityHashMap<Ast.TryStmt, Binding> catchBindings =
                new IdentityHashMap<>();
        private final IdentityHashMap<Ast.NameExpr, Binding> resolvedNames =
                new IdentityHashMap<>();

        private final IdentityHashMap<Ast.AwaitExpr, LinkedHashSet<Binding>> liveAtAwait =
                new IdentityHashMap<>();
        private final IdentityHashMap<Ast.AwaitExpr, Integer> tempsAtAwait =
                new IdentityHashMap<>();
        private final ArrayList<Ast.AwaitExpr> awaitOrder = new ArrayList<>();

        private Binding declare(Scope scope, String name, Ast.TypeRef type) {
            if (name == null || name.isBlank() || "_".equals(name)) return null;
            Binding binding = new Binding(bindings.size(), name, type);
            bindings.add(binding);
            scope.define(binding);
            return binding;
        }

        /** Every source block creates a child lexical scope. */
        private void resolveBlock(List<Ast.Stmt> statements, Scope parent) {
            Scope scope = new Scope(parent);
            for (Ast.Stmt statement : statements) {
                resolveStatement(statement, scope);
            }
        }

        private void resolveStatement(Ast.Stmt statement, Scope scope) {
            if (statement instanceof Ast.BindingStmt source) {
                if (source.initializer() instanceof Ast.LambdaExpr) {
                    Binding binding = declare(scope, source.name(), source.declaredType());
                    bindingStatements.put(source, binding);
                    resolveExpression(source.initializer(), scope);
                } else {
                    resolveExpression(source.initializer(), scope);
                    Binding binding = declare(scope, source.name(), source.declaredType());
                    bindingStatements.put(source, binding);
                }
                return;
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                resolveExpression(destructure.initializer(), scope);
                for (Ast.DestructureBinding item : destructure.bindings()) {
                    if (item.isDiscard()) continue;
                    Binding binding = declare(scope, item.name(), null);
                    destructureBindings.put(item, binding);
                }
                return;
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                resolveExpression(returned.value(), scope);
                return;
            }
            if (statement instanceof Ast.ExprStmt expression) {
                resolveExpression(expression.expression(), scope);
                return;
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                resolveExpression(deferred.expression(), scope);
                return;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    resolveExpression(branch.condition(), scope);
                    resolveBlock(branch.body(), scope);
                }
                resolveBlock(conditional.elseBody(), scope);
                return;
            }
            if (statement instanceof Ast.TryStmt tried) {
                resolveBlock(tried.body(), scope);

                Scope caught = new Scope(scope);
                Binding error = declare(caught, tried.errorName(), null);
                catchBindings.put(tried, error);
                resolveBlock(tried.catchBody(), caught);

                resolveBlock(tried.finallyBody(), scope);
                return;
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                resolveExpression(loop.iterable(), scope);
                Scope iteration = new Scope(scope);
                Binding item = declare(iteration, loop.bindingName(), null);
                forOfBindings.put(loop, item);
                resolveBlock(loop.body(), iteration);
                return;
            }
            if (statement instanceof Ast.ForStmt loop) {
                Scope loopScope = new Scope(scope);
                if (loop.initializer() != null) {
                    resolveStatement(loop.initializer(), loopScope);
                }
                resolveExpression(loop.condition(), loopScope);
                resolveExpression(loop.update(), loopScope);
                resolveBlock(loop.body(), loopScope);
                return;
            }
            throw new IllegalArgumentException(
                    "unsupported async binding-resolution statement "
                            + statement.getClass().getName());
        }

        private void resolveExpression(Ast.Expr expression, Scope scope) {
            if (expression == null) return;

            if (expression instanceof Ast.NameExpr name) {
                Binding binding = scope.lookup(name.name());
                if (binding != null) resolvedNames.put(name, binding);
                return;
            }
            if (expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.LambdaExpr) {
                return;
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                resolveExpression(awaited.expression(), scope);
                return;
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                resolveExpression(unary.operand(), scope);
                return;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                resolveExpression(binary.left(), scope);
                resolveExpression(binary.right(), scope);
                return;
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                resolveExpression(conditional.condition(), scope);
                resolveExpression(conditional.whenTrue(), scope);
                resolveExpression(conditional.whenFalse(), scope);
                return;
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                resolveExpression(assignment.target(), scope);
                resolveExpression(assignment.value(), scope);
                return;
            }
            if (expression instanceof Ast.CallExpr call) {
                resolveExpression(call.callee(), scope);
                for (Ast.Expr argument : call.arguments()) resolveExpression(argument, scope);
                return;
            }
            if (expression instanceof Ast.MemberExpr member) {
                resolveExpression(member.receiver(), scope);
                return;
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                resolveExpression(indexed.receiver(), scope);
                resolveExpression(indexed.index(), scope);
                return;
            }
            if (expression instanceof Ast.NewExpr created) {
                for (Ast.Expr argument : created.arguments()) resolveExpression(argument, scope);
                return;
            }
            if (expression instanceof Ast.SpawnExpr spawned) {
                resolveExpression(spawned.call(), scope);
                return;
            }
            if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) resolveExpression(item, scope);
                return;
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) resolveExpression(item, scope);
                return;
            }
            if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) resolveExpression(field.value(), scope);
                return;
            }
            throw new IllegalArgumentException(
                    "unsupported async binding-resolution expression "
                            + expression.getClass().getName());
        }

        private void collectSuspensions(List<Ast.Stmt> statements) {
            for (Ast.Stmt statement : statements) collectSuspensions(statement);
        }

        private void collectSuspensions(Ast.Stmt statement) {
            if (statement instanceof Ast.BindingStmt binding) {
                collectSuspensions(binding.initializer(), 0);
                return;
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                collectSuspensions(destructure.initializer(), 0);
                return;
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                collectSuspensions(returned.value(), 0);
                return;
            }
            if (statement instanceof Ast.ExprStmt expression) {
                collectSuspensions(expression.expression(), 0);
                return;
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                collectSuspensions(deferred.expression(), 0);
                return;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    collectSuspensions(branch.condition(), 0);
                    collectSuspensions(branch.body());
                }
                collectSuspensions(conditional.elseBody());
                return;
            }
            if (statement instanceof Ast.TryStmt tried) {
                collectSuspensions(tried.body());
                collectSuspensions(tried.catchBody());
                collectSuspensions(tried.finallyBody());
                return;
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                collectSuspensions(loop.iterable(), 0);
                collectSuspensions(loop.body());
                return;
            }
            if (statement instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) collectSuspensions(loop.initializer());
                collectSuspensions(loop.condition(), 0);
                collectSuspensions(loop.body());
                collectSuspensions(loop.update(), 0);
                return;
            }
            throw new IllegalArgumentException(
                    "unsupported async suspension statement "
                            + statement.getClass().getName());
        }

        /**
         * Forward evaluation-order walk used only to determine how many
         * already-computed expression values must survive each await.
         */
        private void collectSuspensions(Ast.Expr expression, int heldTemporaries) {
            if (expression == null) return;

            if (expression instanceof Ast.AwaitExpr awaited) {
                collectSuspensions(awaited.expression(), heldTemporaries);
                rememberAwait(awaited, heldTemporaries);
                return;
            }
            if (expression instanceof Ast.NameExpr
                    || expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.LambdaExpr) {
                return;
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                collectSuspensions(unary.operand(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                collectSuspensions(binary.left(), heldTemporaries);
                collectSuspensions(binary.right(), heldTemporaries + 1);
                return;
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                collectSuspensions(conditional.condition(), heldTemporaries);
                collectSuspensions(conditional.whenTrue(), heldTemporaries);
                collectSuspensions(conditional.whenFalse(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                collectSuspensions(assignment.value(), heldTemporaries);
                collectSuspensions(assignment.target(), heldTemporaries + 1);
                return;
            }
            if (expression instanceof Ast.CallExpr call) {
                collectCallSuspensions(call, heldTemporaries);
                return;
            }
            if (expression instanceof Ast.MemberExpr member) {
                collectSuspensions(member.receiver(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                collectSuspensions(indexed.receiver(), heldTemporaries);
                collectSuspensions(indexed.index(), heldTemporaries + 1);
                return;
            }
            if (expression instanceof Ast.NewExpr created) {
                for (int i = 0; i < created.arguments().size(); i++) {
                    collectSuspensions(created.arguments().get(i), heldTemporaries + i);
                }
                return;
            }
            if (expression instanceof Ast.SpawnExpr spawned) {
                collectCallSuspensions(spawned.call(), heldTemporaries);
                return;
            }
            if (expression instanceof Ast.ListExpr list) {
                for (int i = 0; i < list.elements().size(); i++) {
                    collectSuspensions(list.elements().get(i), heldTemporaries + i);
                }
                return;
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                for (int i = 0; i < tuple.elements().size(); i++) {
                    collectSuspensions(tuple.elements().get(i), heldTemporaries + i);
                }
                return;
            }
            if (expression instanceof Ast.ObjectExpr object) {
                for (int i = 0; i < object.fields().size(); i++) {
                    collectSuspensions(object.fields().get(i).value(), heldTemporaries + i);
                }
                return;
            }
            throw new IllegalArgumentException(
                    "unsupported async suspension expression "
                            + expression.getClass().getName());
        }

        private void collectCallSuspensions(Ast.CallExpr call, int heldTemporaries) {
            int heldByCallee;
            if (call.callee() instanceof Ast.NameExpr) {
                heldByCallee = 0;
            } else if (call.callee() instanceof Ast.MemberExpr member) {
                collectSuspensions(member.receiver(), heldTemporaries);
                heldByCallee = 1;
            } else {
                collectSuspensions(call.callee(), heldTemporaries);
                heldByCallee = 1;
            }

            for (int i = 0; i < call.arguments().size(); i++) {
                collectSuspensions(
                        call.arguments().get(i),
                        heldTemporaries + heldByCallee + i);
            }
        }

        private void rememberAwait(Ast.AwaitExpr awaited, int heldTemporaries) {
            if (!tempsAtAwait.containsKey(awaited)) {
                awaitOrder.add(awaited);
                tempsAtAwait.put(awaited, heldTemporaries);
            } else {
                tempsAtAwait.merge(awaited, heldTemporaries, Math::max);
            }
        }

        private Set<Binding> liveBlock(List<Ast.Stmt> statements, Set<Binding> liveAfter) {
            LinkedHashSet<Binding> live = copy(liveAfter);
            for (int i = statements.size() - 1; i >= 0; i--) {
                live = copy(liveStatement(statements.get(i), live));
            }
            return Set.copyOf(live);
        }

        private Set<Binding> liveStatement(Ast.Stmt statement, Set<Binding> liveAfter) {
            if (statement instanceof Ast.BindingStmt source) {
                Binding binding = bindingStatements.get(source);
                LinkedHashSet<Binding> before = copy(liveAfter);
                if (binding != null) before.remove(binding);
                return liveExpression(source.initializer(), before);
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                LinkedHashSet<Binding> before = copy(liveAfter);
                for (Ast.DestructureBinding item : destructure.bindings()) {
                    Binding binding = destructureBindings.get(item);
                    if (binding != null) before.remove(binding);
                }
                return liveExpression(destructure.initializer(), before);
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                return returned.value() == null
                        ? Set.of()
                        : liveExpression(returned.value(), Set.of());
            }
            if (statement instanceof Ast.ExprStmt expression) {
                return liveExpression(expression.expression(), liveAfter);
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                return liveExpression(deferred.expression(), liveAfter);
            }
            if (statement instanceof Ast.IfStmt conditional) {
                LinkedHashSet<Binding> alternatives = new LinkedHashSet<>();
                for (Ast.IfBranch branch : conditional.branches()) {
                    alternatives.addAll(liveBlock(branch.body(), liveAfter));
                }
                alternatives.addAll(liveBlock(conditional.elseBody(), liveAfter));

                Set<Binding> before = alternatives;
                for (int i = conditional.branches().size() - 1; i >= 0; i--) {
                    before = liveExpression(
                            conditional.branches().get(i).condition(),
                            before);
                }
                return before;
            }
            if (statement instanceof Ast.TryStmt tried) {
                Set<Binding> beforeFinally = liveBlock(tried.finallyBody(), liveAfter);
                Set<Binding> beforeBody = liveBlock(tried.body(), beforeFinally);

                LinkedHashSet<Binding> beforeCatch =
                        copy(liveBlock(tried.catchBody(), beforeFinally));
                Binding error = catchBindings.get(tried);
                if (error != null) beforeCatch.remove(error);

                return union(beforeBody, beforeCatch);
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                Binding item = forOfBindings.get(loop);
                Set<Binding> head = Set.copyOf(liveAfter);

                for (int iteration = 0; iteration < MAX_FIXED_POINT_ITERATIONS; iteration++) {
                    LinkedHashSet<Binding> beforeBody =
                            copy(liveBlock(loop.body(), head));
                    if (item != null) beforeBody.remove(item);

                    Set<Binding> next = union(liveAfter, beforeBody);
                    if (next.equals(head)) {
                        return liveExpression(loop.iterable(), next);
                    }
                    head = next;
                }
                throw new IllegalStateException("for-of async liveness did not converge");
            }
            if (statement instanceof Ast.ForStmt loop) {
                Set<Binding> head = Set.copyOf(liveAfter);

                for (int iteration = 0; iteration < MAX_FIXED_POINT_ITERATIONS; iteration++) {
                    Set<Binding> beforeUpdate = loop.update() == null
                            ? head
                            : liveExpression(loop.update(), head);
                    Set<Binding> beforeBody = liveBlock(loop.body(), beforeUpdate);
                    Set<Binding> conditionAfter = union(liveAfter, beforeBody);
                    Set<Binding> next = loop.condition() == null
                            ? conditionAfter
                            : liveExpression(loop.condition(), conditionAfter);

                    if (next.equals(head)) {
                        return loop.initializer() == null
                                ? next
                                : liveStatement(loop.initializer(), next);
                    }
                    head = next;
                }
                throw new IllegalStateException("for-loop async liveness did not converge");
            }
            throw new IllegalArgumentException(
                    "unsupported async liveness statement "
                            + statement.getClass().getName());
        }

        private Set<Binding> liveExpression(Ast.Expr expression, Set<Binding> liveAfter) {
            if (expression == null) return Set.copyOf(liveAfter);

            if (expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.LambdaExpr) {
                return Set.copyOf(liveAfter);
            }
            if (expression instanceof Ast.NameExpr name) {
                LinkedHashSet<Binding> live = copy(liveAfter);
                Binding binding = resolvedNames.get(name);
                if (binding != null) live.add(binding);
                return Set.copyOf(live);
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                recordLive(awaited, liveAfter);
                return liveExpression(awaited.expression(), liveAfter);
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                return liveExpression(unary.operand(), liveAfter);
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                Set<Binding> beforeRight = liveExpression(binary.right(), liveAfter);
                return liveExpression(binary.left(), beforeRight);
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                Set<Binding> trueLive =
                        liveExpression(conditional.whenTrue(), liveAfter);
                Set<Binding> falseLive =
                        liveExpression(conditional.whenFalse(), liveAfter);
                return liveExpression(
                        conditional.condition(),
                        union(trueLive, falseLive));
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                if (assignment.target() instanceof Ast.NameExpr name) {
                    Binding target = resolvedNames.get(name);
                    if (target != null) {
                        LinkedHashSet<Binding> before = copy(liveAfter);
                        before.remove(target);
                        return liveExpression(assignment.value(), before);
                    }
                }

                Set<Binding> beforeTarget =
                        liveExpression(assignment.target(), liveAfter);
                return liveExpression(assignment.value(), beforeTarget);
            }
            if (expression instanceof Ast.CallExpr call) {
                Set<Binding> live = Set.copyOf(liveAfter);
                for (int i = call.arguments().size() - 1; i >= 0; i--) {
                    live = liveExpression(call.arguments().get(i), live);
                }
                return liveExpression(call.callee(), live);
            }
            if (expression instanceof Ast.MemberExpr member) {
                return liveExpression(member.receiver(), liveAfter);
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                Set<Binding> beforeIndex =
                        liveExpression(indexed.index(), liveAfter);
                return liveExpression(indexed.receiver(), beforeIndex);
            }
            if (expression instanceof Ast.NewExpr created) {
                Set<Binding> live = Set.copyOf(liveAfter);
                for (int i = created.arguments().size() - 1; i >= 0; i--) {
                    live = liveExpression(created.arguments().get(i), live);
                }
                return live;
            }
            if (expression instanceof Ast.SpawnExpr spawned) {
                return liveExpression(spawned.call(), liveAfter);
            }
            if (expression instanceof Ast.ListExpr list) {
                Set<Binding> live = Set.copyOf(liveAfter);
                for (int i = list.elements().size() - 1; i >= 0; i--) {
                    live = liveExpression(list.elements().get(i), live);
                }
                return live;
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                Set<Binding> live = Set.copyOf(liveAfter);
                for (int i = tuple.elements().size() - 1; i >= 0; i--) {
                    live = liveExpression(tuple.elements().get(i), live);
                }
                return live;
            }
            if (expression instanceof Ast.ObjectExpr object) {
                Set<Binding> live = Set.copyOf(liveAfter);
                for (int i = object.fields().size() - 1; i >= 0; i--) {
                    live = liveExpression(object.fields().get(i).value(), live);
                }
                return live;
            }
            throw new IllegalArgumentException(
                    "unsupported async liveness expression "
                            + expression.getClass().getName());
        }

        private void recordLive(Ast.AwaitExpr awaited, Set<Binding> liveAfter) {
            liveAtAwait.computeIfAbsent(
                            awaited,
                            ignored -> new LinkedHashSet<>())
                    .addAll(liveAfter);
            if (!tempsAtAwait.containsKey(awaited)) rememberAwait(awaited, 0);
        }

        private Result finish() {
            LinkedHashSet<Binding> spilled = new LinkedHashSet<>();
            for (Ast.AwaitExpr awaited : awaitOrder) {
                Set<Binding> live = liveAtAwait.get(awaited);
                if (live != null) spilled.addAll(live);
            }

            ArrayList<SpillSlot> slots = new ArrayList<>();
            for (Binding binding : bindings) {
                if (!spilled.contains(binding)) continue;
                slots.add(new SpillSlot(
                        slots.size(),
                        binding.id(),
                        binding.name(),
                        binding.type(),
                        isReferenceLike(binding.type())));
            }

            ArrayList<SuspensionPoint> points = new ArrayList<>(awaitOrder.size());
            int maxTemps = 0;

            for (int pc = 0; pc < awaitOrder.size(); pc++) {
                Ast.AwaitExpr awaited = awaitOrder.get(pc);
                Set<Binding> live = liveAtAwait.getOrDefault(
                        awaited,
                        new LinkedHashSet<>());

                ArrayList<Integer> bindingIds = new ArrayList<>();
                LinkedHashSet<String> names = new LinkedHashSet<>();
                ArrayList<Integer> liveSlots = new ArrayList<>();
                ArrayList<Integer> clearSlots = new ArrayList<>();

                for (SpillSlot slot : slots) {
                    Binding binding = bindings.get(slot.bindingId());
                    if (live.contains(binding)) {
                        bindingIds.add(binding.id());
                        names.add(binding.name());
                        liveSlots.add(slot.index());
                    } else if (slot.referenceLike()) {
                        clearSlots.add(slot.index());
                    }
                }

                int temporaryCount = tempsAtAwait.getOrDefault(awaited, 0);
                maxTemps = Math.max(maxTemps, temporaryCount);
                points.add(new SuspensionPoint(
                        pc,
                        awaited,
                        bindingIds,
                        names,
                        liveSlots,
                        clearSlots,
                        temporaryCount));
            }

            return new Result(slots, maxTemps, points);
        }

        private static boolean isReferenceLike(Ast.TypeRef type) {
            if (type == null) return true;
            return switch (type.name()) {
                case "bool", "boolean",
                        "byte", "short", "int", "long",
                        "float", "double", "char",
                        "i8", "i16", "i32", "i64",
                        "u8", "u16", "u32", "u64",
                        "f32", "f64" -> false;
                default -> true;
            };
        }

        private static LinkedHashSet<Binding> copy(Set<Binding> values) {
            return new LinkedHashSet<>(values);
        }

        private static Set<Binding> union(Set<Binding> left, Set<Binding> right) {
            LinkedHashSet<Binding> result = copy(left);
            result.addAll(right);
            return Set.copyOf(result);
        }
    }
}
