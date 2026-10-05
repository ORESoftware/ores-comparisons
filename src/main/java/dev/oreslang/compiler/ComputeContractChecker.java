package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic gate for data-oriented compute, dataflow, and static aspects.
 *
 * <p>This pass runs after ordinary type checking. It intentionally validates a
 * conservative AOT/device-safe subset: compute functions cannot acquire ambient
 * I/O/process/actor authority, await, allocate object/list state, create
 * closures, or call arbitrary host-style methods. Parallel/SIMD loop markers
 * are legal only inside compute functions and remain optimization requests:
 * a backend must still prove a legal lowering before executing iterations in
 * parallel.</p>
 */
public final class ComputeContractChecker {
    private static final Set<String> EFFECT_ANNOTATIONS = Set.of(
            "Reads", "Writes", "Discards", "Reduces", "Atomic", "Layout", "Place");
    private static final Set<String> PURE_INTRINSICS = Set.of(
            "abs", "min", "max", "sqrt", "sin", "cos", "tan",
            "exp", "log", "floor", "ceil", "round");

    private final Map<String, Ast.FunctionDecl> functions = new LinkedHashMap<>();
    private final Map<String, Ast.FunctionDecl> simpleFunctions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private record OwnedFlow(String module, Ast.FlowDecl declaration) { }
    private record OwnedAspect(String module, Ast.AspectDecl declaration) { }

    private final List<OwnedFlow> flows = new ArrayList<>();
    private final List<OwnedAspect> aspects = new ArrayList<>();
    private final Map<String, LinkedHashSet<String>> computeCalls = new LinkedHashMap<>();

    private ComputeContractChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        ComputeContractChecker checker = new ComputeContractChecker(program);
        checker.validate(program);
        return program;
    }

    public record RegionEffect(
            String rootParameter,
            String fieldPath,
            String privilege,
            String reductionOperator) { }

    public record ComputeContract(
            String name,
            List<RegionEffect> effects,
            Map<String, String> layouts,
            String placement) {
        public ComputeContract {
            effects = List.copyOf(effects);
            layouts = Map.copyOf(layouts);
        }
    }

    /** Extracts normalized backend metadata from a checked compute function. */
    public static ComputeContract contractOf(Ast.FunctionDecl fn) {
        if (!isCompute(fn)) {
            throw new IllegalArgumentException("function is not a compute fnc: " + fn.name());
        }
        ArrayList<RegionEffect> effects = new ArrayList<>();
        LinkedHashMap<String, String> layouts = new LinkedHashMap<>();
        String placement = "auto";

        for (Ast.Annotation annotation : fn.annotations()) {
            switch (annotation.name()) {
                case "Reads", "Writes", "Discards", "Atomic" -> {
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        String[] path = splitPath(argument.name());
                        effects.add(new RegionEffect(
                                path[0],
                                path[1],
                                annotation.name().toLowerCase(),
                                null));
                    }
                }
                case "Reduces" -> {
                    String[] path = splitPath(annotation.arguments().get(0).name());
                    effects.add(new RegionEffect(
                            path[0],
                            path[1],
                            "reduces",
                            annotation.arguments().get(1).name()));
                }
                case "Layout" -> layouts.put(
                        annotation.arguments().get(0).name(),
                        annotation.arguments().get(1).name());
                case "Place" -> placement = annotation.arguments().getFirst().name();
                default -> { }
            }
        }

        return new ComputeContract(fn.name(), effects, layouts, placement);
    }

    public static boolean isCompute(Ast.FunctionDecl fn) {
        return hasAnnotation(fn.annotations(), "Compute");
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    String qualified = qualify(module.name(), fn.name());
                    functions.put(qualified, fn);
                    Ast.FunctionDecl previous = simpleFunctions.putIfAbsent(fn.name(), fn);
                    if (previous != null && previous != fn) {
                        simpleFunctions.remove(fn.name());
                        ambiguousFunctions.add(fn.name());
                    }
                } else if (declaration instanceof Ast.FlowDecl flow) {
                    flows.add(new OwnedFlow(module.name(), flow));
                } else if (declaration instanceof Ast.AspectDecl aspect) {
                    aspects.add(new OwnedAspect(module.name(), aspect));
                }
            }
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    validateFunction(module.name(), fn);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        validateLoopExecution(
                                method.body(),
                                false,
                                "method " + klass.name() + "." + method.name());
                    }
                }
            }
        }

        validateMetadataNames(program);
        validateComputeCallGraph();
        for (OwnedFlow flow : flows) validateFlow(flow.module(), flow.declaration());
        for (OwnedAspect aspect : aspects) validateAspect(aspect.module(), aspect.declaration());
    }

    private void validateMetadataNames(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            LinkedHashMap<String, String> occupied = new LinkedHashMap<>();
            for (Ast.Decl declaration : module.declarations()) {
                String name = null;
                String kind = null;
                if (declaration instanceof Ast.FunctionDecl fn) {
                    name = fn.name(); kind = "callable";
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    name = klass.name(); kind = "class";
                } else if (declaration instanceof Ast.InterfaceDecl iface) {
                    name = iface.name(); kind = "interface";
                } else if (declaration instanceof Ast.FieldDecl field) {
                    name = field.name(); kind = "binding";
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    name = alias.name(); kind = "type alias";
                } else if (declaration instanceof Ast.FlowDecl flow) {
                    name = flow.name(); kind = "flow";
                } else if (declaration instanceof Ast.AspectDecl aspect) {
                    name = aspect.name(); kind = "aspect";
                }
                if (name == null) continue;
                String previous = occupied.putIfAbsent(name, kind);
                if (previous != null
                        && (kind.equals("flow")
                            || kind.equals("aspect")
                            || previous.equals("flow")
                            || previous.equals("aspect"))) {
                    throw new IllegalArgumentException(
                            "compile-time declaration '" + qualify(module.name(), name)
                                    + "' collides between " + previous + " and " + kind);
                }
            }
        }
    }

    private record RegionAccessPath(String root, String field) { }

    private record ComputeScope(
            String module,
            String functionId,
            Ast.FunctionDecl function,
            Set<String> regionParameters,
            List<RegionEffect> effects) { }

    private void validateFunction(String module, Ast.FunctionDecl fn) {
        boolean compute = isCompute(fn);
        boolean hasEffectMetadata = fn.annotations().stream()
                .anyMatch(annotation -> EFFECT_ANNOTATIONS.contains(annotation.name()));

        if (!compute && hasEffectMetadata) {
            throw new IllegalArgumentException(
                    "region effects/layout/placement require 'compute fnc': " + qualify(module, fn.name()));
        }

        if (!compute) {
            validateLoopExecution(fn.body(), false, "function " + qualify(module, fn.name()));
            return;
        }

        if (fn.kind() != Ast.CallableKind.FNC
                || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async()
                || fn.nonLexical()) {
            throw new IllegalArgumentException(
                    "compute fnc must be synchronous, non-actor, and lexical: " + qualify(module, fn.name()));
        }
        if (!fn.genericParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "compute fnc generic specialization is not part of the AOT floor yet: "
                            + qualify(module, fn.name()));
        }

        Map<String, Ast.Param> params = new LinkedHashMap<>();
        LinkedHashSet<String> locals = new LinkedHashSet<>();
        LinkedHashSet<String> regionParameters = new LinkedHashSet<>();
        for (Ast.Param param : fn.parameters()) {
            params.put(param.name(), param);
            locals.add(param.name());
            Ast.TypeRef type = param.type();
            if (type != null && (type.name().equals("Region") || type.name().equals("RegionView"))) {
                regionParameters.add(param.name());
            }
        }

        validateComputeMetadata(fn, params);
        validateLoopExecution(fn.body(), true, "compute fnc " + qualify(module, fn.name()));

        String functionId = qualify(module, fn.name());
        computeCalls.putIfAbsent(functionId, new LinkedHashSet<>());
        ComputeScope scope = new ComputeScope(
                module,
                functionId,
                fn,
                Set.copyOf(regionParameters),
                contractOf(fn).effects());
        validateComputeBody(fn.body(), scope, locals);
    }

    private void validateComputeMetadata(Ast.FunctionDecl fn, Map<String, Ast.Param> params) {
        int placeCount = 0;
        Set<String> layoutTargets = new LinkedHashSet<>();
        LinkedHashMap<String, String> effectClaims = new LinkedHashMap<>();

        for (Ast.Annotation annotation : fn.annotations()) {
            switch (annotation.name()) {
                case "Reads", "Writes", "Discards", "Atomic" -> {
                    if (annotation.arguments().isEmpty()) {
                        throw new IllegalArgumentException(
                                annotation.name().toLowerCase() + " requires at least one region path");
                    }
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        String privilege = annotation.name().toLowerCase();
                        validateRegionPath(argument, params, privilege);
                        claimEffectPath(effectClaims, argument.name(), privilege);
                    }
                }
                case "Reduces" -> {
                    if (annotation.arguments().size() != 2) {
                        throw new IllegalArgumentException(
                                "reduces requires exactly a region path and reduction operator");
                    }
                    validateRegionPath(annotation.arguments().get(0), params, "reduces");
                    claimEffectPath(
                            effectClaims,
                            annotation.arguments().get(0).name(),
                            "reduces");
                    Ast.TypeRef operator = annotation.arguments().get(1);
                    requireMetadataAtom(operator, "reduction operator");
                }
                case "Layout" -> {
                    if (annotation.arguments().size() != 2) {
                        throw new IllegalArgumentException(
                                "layout requires a region parameter and layout kind");
                    }
                    Ast.TypeRef target = annotation.arguments().get(0);
                    Ast.TypeRef layout = annotation.arguments().get(1);
                    requireMetadataAtom(target, "layout target");
                    requireRegionParameter(params, target.name(), "layout");
                    requireMetadataAtom(layout, "layout kind");
                    if (!Set.of("auto", "aos", "soa", "aosoa", "tiled").contains(layout.name())) {
                        throw new IllegalArgumentException("unknown region layout '" + layout.name() + "'");
                    }
                    if (!layoutTargets.add(target.name())) {
                        throw new IllegalArgumentException(
                                "duplicate layout declaration for region parameter '" + target.name() + "'");
                    }
                }
                case "Place" -> {
                    placeCount++;
                    if (placeCount > 1) {
                        throw new IllegalArgumentException("compute fnc may declare at most one place clause");
                    }
                    if (annotation.arguments().size() != 1) {
                        throw new IllegalArgumentException("place requires exactly one of auto, cpu, or gpu");
                    }
                    Ast.TypeRef placement = annotation.arguments().getFirst();
                    requireMetadataAtom(placement, "placement");
                    if (!Set.of("auto", "cpu", "gpu").contains(placement.name())) {
                        throw new IllegalArgumentException("unknown compute placement '" + placement.name() + "'");
                    }
                }
                default -> { }
            }
        }
    }

    private static void claimEffectPath(
            Map<String, String> claims,
            String path,
            String privilege) {
        String previous = claims.putIfAbsent(path, privilege);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "region effect footprint '" + path
                            + "' is declared more than once ("
                            + previous + " and " + privilege + ")");
        }
    }

    private void validateRegionPath(
            Ast.TypeRef path,
            Map<String, Ast.Param> params,
            String clause) {
        requireMetadataAtom(path, clause + " target");
        String[] split = splitPath(path.name());
        requireRegionParameter(params, split[0], clause);
    }

    private void requireRegionParameter(
            Map<String, Ast.Param> params,
            String name,
            String clause) {
        Ast.Param parameter = params.get(name);
        if (parameter == null) {
            throw new IllegalArgumentException(
                    clause + " target '" + name + "' is not a parameter of this compute fnc");
        }
        Ast.TypeRef type = parameter.type();
        if (!type.name().equals("Region") && !type.name().equals("RegionView")) {
            throw new IllegalArgumentException(
                    clause + " target '" + name + "' must have Region<T> or RegionView<T> type");
        }
        if (type.arguments().size() != 1 || type.inferArguments()) {
            throw new IllegalArgumentException(
                    type.name() + " requires exactly one explicit element type");
        }
    }

    private static void requireMetadataAtom(Ast.TypeRef value, String what) {
        if (value == null
                || value.inferArguments()
                || value.isBorrow()
                || value.isStringLiteral()
                || value.isRecordType()
                || value.isTupleType()
                || value.isUnion()
                || !value.arguments().isEmpty()) {
            throw new IllegalArgumentException(what + " must be a simple static name/path");
        }
    }

    private void validateLoopExecution(List<Ast.Stmt> body, boolean compute, String owner) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ForOfStmt loop) {
                validateExecution(loop.execution(), compute, owner);
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                validateExecution(loop.execution(), compute, owner);
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.ForStmt loop) {
                validateExecution(loop.execution(), compute, owner);
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.LoopStmt loop) {
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.BlockStmt block) {
                validateLoopExecution(block.body(), compute, owner);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateLoopExecution(branch.body(), compute, owner);
                }
                validateLoopExecution(conditional.elseBody(), compute, owner);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                validateLoopExecution(attempted.body(), compute, owner);
                validateLoopExecution(attempted.catchBody(), compute, owner);
                validateLoopExecution(attempted.finallyBody(), compute, owner);
            }
        }
    }

    private static void validateExecution(
            Ast.LoopExecution execution,
            boolean compute,
            String owner) {
        if (execution != Ast.LoopExecution.SEQUENTIAL && !compute) {
            throw new IllegalArgumentException(
                    execution.name().toLowerCase()
                            + " loop execution is only legal inside compute fnc; found in " + owner);
        }
    }

    private void validateComputeBody(
            List<Ast.Stmt> body,
            ComputeScope scope,
            Set<String> incomingLocals) {
        LinkedHashSet<String> locals = new LinkedHashSet<>(incomingLocals);

        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.DeferStmt) {
                throw unsafe(scope.function().name(), "defer");
            }
            if (stmt instanceof Ast.TryStmt) {
                throw unsafe(scope.function().name(), "try/catch/finally");
            }
            if (stmt instanceof Ast.LoopStmt) {
                throw unsafe(scope.function().name(), "unbounded loop");
            }

            if (stmt instanceof Ast.BindingStmt binding) {
                validateComputeExpr(binding.initializer(), scope, locals);
                locals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                validateComputeExpr(destructure.initializer(), scope, locals);
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) locals.add(binding.name());
                }
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                validateComputeExpr(returned.value(), scope, locals);
            } else if (stmt instanceof Ast.ExprStmt expression) {
                validateComputeExpr(expression.expression(), scope, locals);
            } else if (stmt instanceof Ast.BlockStmt nested) {
                validateComputeBody(nested.body(), scope, locals);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateComputeExpr(branch.condition(), scope, locals);
                    validateComputeBody(branch.body(), scope, locals);
                }
                validateComputeBody(conditional.elseBody(), scope, locals);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                validateComputeExpr(loop.iterable(), scope, locals);
                LinkedHashSet<String> loopLocals = new LinkedHashSet<>(locals);
                loopLocals.add(loop.bindingName());
                validateComputeBody(loop.body(), scope, loopLocals);
            } else if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                validateComputeExpr(loop.iterable(), scope, locals);
                LinkedHashSet<String> loopLocals = new LinkedHashSet<>(locals);
                for (Ast.DestructureBinding binding : loop.bindings()) {
                    if (!binding.isDiscard()) loopLocals.add(binding.name());
                }
                validateComputeBody(loop.body(), scope, loopLocals);
            } else if (stmt instanceof Ast.ForStmt loop) {
                validateCanonicalBoundedFor(loop, scope, locals);
                LinkedHashSet<String> loopLocals = new LinkedHashSet<>(locals);
                Ast.BindingStmt binding = (Ast.BindingStmt) loop.initializer();
                validateComputeExpr(binding.initializer(), scope, loopLocals);
                loopLocals.add(binding.name());
                validateComputeExpr(loop.condition(), scope, loopLocals);
                validateComputeExpr(loop.update(), scope, loopLocals);
                validateComputeBody(loop.body(), scope, loopLocals);
            }
        }
    }

    private enum ForDirection { UP, DOWN }

    private record BoundedForShape(
            String inductionVariable,
            ForDirection direction,
            Ast.Expr boundExpression,
            String boundName,
            boolean inclusive) { }

    /**
     * Device/AOT compute loops must expose a finite iteration space that a
     * backend can lower without executing an arbitrary host-style while loop.
     * The v0 floor accepts canonical induction only:
     *
     * <pre>
     * for int i = start; i < bound; i++ { ... }
     * for int i = start; i >= bound; i = i - 2 { ... }
     * </pre>
     *
     * The bound is a literal or an already-existing scalar/local name and
     * neither the induction variable nor a named bound may be assigned in the
     * body. More expressive proofs can be added later without weakening this
     * conservative floor.
     */
    private static void validateCanonicalBoundedFor(
            Ast.ForStmt loop,
            ComputeScope scope,
            Set<String> incomingLocals) {
        String owner = scope.function().name();
        if (!(loop.initializer() instanceof Ast.BindingStmt initializer)) {
            throw unsafe(owner, "non-canonical compute for-loop initializer");
        }
        if (!(loop.condition() instanceof Ast.BinaryExpr condition)) {
            throw unsafe(owner, "compute for-loop without a provable monotonic bound");
        }
        if (loop.update() == null) {
            throw unsafe(owner, "compute for-loop without a monotonic induction update");
        }

        BoundedForShape shape = boundedForShape(initializer.name(), condition, incomingLocals);
        if (shape == null) {
            throw unsafe(owner, "compute for-loop without a canonical finite induction bound");
        }
        if (!hasUnitStep(loop.update(), shape.inductionVariable(), shape.direction())) {
            throw unsafe(
                    owner,
                    "compute for-loop requires unit-step induction in the v0 device-safe subset");
        }
        if (!hasRepresentableTripCount(initializer.initializer(), shape)) {
            throw unsafe(
                    owner,
                    "compute for-loop trip count is not provably finite within signed 64-bit induction");
        }

        LinkedHashSet<String> protectedNames = new LinkedHashSet<>();
        protectedNames.add(shape.inductionVariable());
        if (shape.boundName() != null) protectedNames.add(shape.boundName());
        if (statementsMutateAny(loop.body(), protectedNames)) {
            throw unsafe(
                    owner,
                    "compute for-loop mutates its induction variable or loop bound inside the body");
        }
    }

    private static BoundedForShape boundedForShape(
            String induction,
            Ast.BinaryExpr condition,
            Set<String> incomingLocals) {
        Ast.Expr bound;
        ForDirection direction;

        boolean inclusive;
        if (isName(condition.left(), induction)) {
            bound = condition.right();
            direction = switch (condition.operator()) {
                case "<", "<=" -> ForDirection.UP;
                case ">", ">=" -> ForDirection.DOWN;
                default -> null;
            };
            inclusive = condition.operator().equals("<=")
                    || condition.operator().equals(">=");
        } else if (isName(condition.right(), induction)) {
            bound = condition.left();
            direction = switch (condition.operator()) {
                case ">", ">=" -> ForDirection.UP;
                case "<", "<=" -> ForDirection.DOWN;
                default -> null;
            };
            inclusive = condition.operator().equals("<=")
                    || condition.operator().equals(">=");
        } else {
            return null;
        }
        if (direction == null) return null;

        if (bound instanceof Ast.LiteralExpr literal) {
            if (!(literal.value() instanceof Long)) return null;
            return new BoundedForShape(induction, direction, bound, null, inclusive);
        }
        if (bound instanceof Ast.NameExpr name
                && incomingLocals.contains(name.name())
                && !name.name().equals(induction)) {
            return new BoundedForShape(induction, direction, bound, name.name(), inclusive);
        }
        return null;
    }

    private static boolean hasUnitStep(
            Ast.Expr update,
            String induction,
            ForDirection direction) {
        if (!(update instanceof Ast.AssignExpr assignment)
                || !isName(assignment.target(), induction)
                || !(assignment.value() instanceof Ast.BinaryExpr binary)) {
            return false;
        }

        if (direction == ForDirection.UP && binary.operator().equals("+")) {
            return (isName(binary.left(), induction) && isLongLiteral(binary.right(), 1L))
                    || (isLongLiteral(binary.left(), 1L) && isName(binary.right(), induction));
        }
        if (direction == ForDirection.DOWN && binary.operator().equals("-")) {
            return isName(binary.left(), induction) && isLongLiteral(binary.right(), 1L);
        }
        return false;
    }

    private static boolean hasRepresentableTripCount(
            Ast.Expr startExpression,
            BoundedForShape shape) {
        Long start = longLiteral(startExpression);
        Long bound = longLiteral(shape.boundExpression());

        if (start != null && bound != null) {
            try {
                long distance;
                if (shape.direction() == ForDirection.UP) {
                    if (start > bound || (!shape.inclusive() && start.equals(bound))) return true;
                    distance = Math.subtractExact(bound, start);
                } else {
                    if (start < bound || (!shape.inclusive() && start.equals(bound))) return true;
                    distance = Math.subtractExact(start, bound);
                }
                if (shape.inclusive()) Math.addExact(distance, 1L);
                return true;
            } catch (ArithmeticException overflow) {
                return false;
            }
        }

        // Dynamic upper bounds are safe for the canonical 0-or-positive start,
        // exclusive ascending unit-step shape: max trip count is Long.MAX_VALUE.
        if (shape.direction() == ForDirection.UP
                && !shape.inclusive()
                && start != null
                && start >= 0L
                && shape.boundName() != null) {
            return true;
        }

        // Dynamic starts are safe against a non-negative exclusive lower bound:
        // max(start - bound) cannot exceed Long.MAX_VALUE.
        return shape.direction() == ForDirection.DOWN
                && !shape.inclusive()
                && start == null
                && startExpression instanceof Ast.NameExpr
                && bound != null
                && bound >= 0L;
    }

    private static boolean isName(Ast.Expr expr, String name) {
        return expr instanceof Ast.NameExpr candidate && candidate.name().equals(name);
    }

    private static Long longLiteral(Ast.Expr expr) {
        return expr instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long value
                ? value
                : null;
    }

    private static boolean isLongLiteral(Ast.Expr expr, long expected) {
        Long value = longLiteral(expr);
        return value != null && value == expected;
    }

    private static boolean statementsMutateAny(
            List<Ast.Stmt> body,
            Set<String> protectedNames) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (protectedNames.contains(binding.name())
                        || expressionMutatesAny(binding.initializer(), protectedNames)) return true;
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard() && protectedNames.contains(binding.name())) return true;
                }
                if (expressionMutatesAny(destructure.initializer(), protectedNames)) return true;
            } else if (stmt instanceof Ast.ExprStmt expression) {
                if (expressionMutatesAny(expression.expression(), protectedNames)) return true;
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                if (expressionMutatesAny(returned.value(), protectedNames)) return true;
            } else if (stmt instanceof Ast.BlockStmt block) {
                if (statementsMutateAny(block.body(), protectedNames)) return true;
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (expressionMutatesAny(branch.condition(), protectedNames)
                            || statementsMutateAny(branch.body(), protectedNames)) return true;
                }
                if (statementsMutateAny(conditional.elseBody(), protectedNames)) return true;
            } else if (stmt instanceof Ast.ForOfStmt nested) {
                if (protectedNames.contains(nested.bindingName())
                        || expressionMutatesAny(nested.iterable(), protectedNames)
                        || statementsMutateAny(nested.body(), protectedNames)) return true;
            } else if (stmt instanceof Ast.ForOfDestructureStmt nested) {
                for (Ast.DestructureBinding binding : nested.bindings()) {
                    if (!binding.isDiscard() && protectedNames.contains(binding.name())) return true;
                }
                if (expressionMutatesAny(nested.iterable(), protectedNames)
                        || statementsMutateAny(nested.body(), protectedNames)) return true;
            } else if (stmt instanceof Ast.ForStmt nested) {
                if (nested.initializer() instanceof Ast.BindingStmt binding
                        && protectedNames.contains(binding.name())) return true;
                if (nested.initializer() instanceof Ast.ExprStmt expression
                        && expressionMutatesAny(expression.expression(), protectedNames)) return true;
                if (expressionMutatesAny(nested.condition(), protectedNames)
                        || expressionMutatesAny(nested.update(), protectedNames)
                        || statementsMutateAny(nested.body(), protectedNames)) return true;
            }
        }
        return false;
    }

    private static boolean expressionMutatesAny(
            Ast.Expr expr,
            Set<String> protectedNames) {
        if (expr == null) return false;
        if (expr instanceof Ast.AssignExpr assignment) {
            if (assignment.target() instanceof Ast.NameExpr name
                    && protectedNames.contains(name.name())) return true;
            return expressionMutatesAny(assignment.target(), protectedNames)
                    || expressionMutatesAny(assignment.value(), protectedNames);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return expressionMutatesAny(binary.left(), protectedNames)
                    || expressionMutatesAny(binary.right(), protectedNames);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return expressionMutatesAny(unary.operand(), protectedNames);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return expressionMutatesAny(conditional.condition(), protectedNames)
                    || expressionMutatesAny(conditional.whenTrue(), protectedNames)
                    || expressionMutatesAny(conditional.whenFalse(), protectedNames);
        }
        if (expr instanceof Ast.MemberExpr member) {
            return expressionMutatesAny(member.receiver(), protectedNames);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return expressionMutatesAny(indexed.receiver(), protectedNames)
                    || expressionMutatesAny(indexed.index(), protectedNames);
        }
        if (expr instanceof Ast.CallExpr call) {
            if (expressionMutatesAny(call.callee(), protectedNames)) return true;
            for (Ast.Expr argument : call.arguments()) {
                if (expressionMutatesAny(argument, protectedNames)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr element : tuple.elements()) {
                if (expressionMutatesAny(element, protectedNames)) return true;
            }
        }
        return false;
    }

    private void validateComputeExpr(
            Ast.Expr expr,
            ComputeScope scope,
            Set<String> locals) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;

        RegionAccessPath regionPath = regionAccessPath(expr, scope.regionParameters());
        if (regionPath != null) {
            validateRegionIndexes(expr, scope, locals);
            requireRegionPermission(regionPath, scope, false);
            return;
        }

        if (expr instanceof Ast.NameExpr name) {
            if (!locals.contains(name.name())) {
                throw unsafe(
                        scope.function().name(),
                        "ambient/global value '" + name.name()
                                + "'; pass it explicitly as a parameter or region input");
            }
            return;
        }
        if (expr instanceof Ast.AwaitExpr) throw unsafe(scope.function().name(), "await");
        if (expr instanceof Ast.NewExpr) throw unsafe(scope.function().name(), "allocation with new");
        if (expr instanceof Ast.LambdaExpr) throw unsafe(scope.function().name(), "closure/lambda creation");
        if (expr instanceof Ast.ListExpr || expr instanceof Ast.ObjectExpr) {
            throw unsafe(scope.function().name(), "heap-backed collection/object literal allocation");
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            validateComputeExpr(binary.left(), scope, locals);
            validateComputeExpr(binary.right(), scope, locals);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            validateComputeExpr(unary.operand(), scope, locals);
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            validateComputeAssignmentTarget(assignment.target(), scope, locals);
            validateComputeExpr(assignment.value(), scope, locals);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            validateComputeExpr(conditional.condition(), scope, locals);
            validateComputeExpr(conditional.whenTrue(), scope, locals);
            validateComputeExpr(conditional.whenFalse(), scope, locals);
            return;
        }
        if (expr instanceof Ast.MemberExpr member) {
            validateComputeExpr(member.receiver(), scope, locals);
            return;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            validateComputeExpr(indexed.receiver(), scope, locals);
            validateComputeExpr(indexed.index(), scope, locals);
            return;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr element : tuple.elements()) validateComputeExpr(element, scope, locals);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            validateComputeCall(call, scope, locals);
        }
    }

    private void validateComputeAssignmentTarget(
            Ast.Expr target,
            ComputeScope scope,
            Set<String> locals) {
        RegionAccessPath regionPath = regionAccessPath(target, scope.regionParameters());
        if (regionPath != null) {
            validateRegionIndexes(target, scope, locals);
            requireRegionPermission(regionPath, scope, true);
            return;
        }
        if (target instanceof Ast.NameExpr name && locals.contains(name.name())) {
            return;
        }
        throw unsafe(
                scope.function().name(),
                "mutation of ambient or non-region object/indexed state");
    }

    private void validateRegionIndexes(
            Ast.Expr expr,
            ComputeScope scope,
            Set<String> locals) {
        if (expr instanceof Ast.IndexExpr indexed) {
            validateComputeExpr(indexed.index(), scope, locals);
            validateRegionIndexes(indexed.receiver(), scope, locals);
        } else if (expr instanceof Ast.MemberExpr member) {
            validateRegionIndexes(member.receiver(), scope, locals);
        }
    }

    private static RegionAccessPath regionAccessPath(
            Ast.Expr expr,
            Set<String> regionParameters) {
        if (expr instanceof Ast.NameExpr name) {
            return regionParameters.contains(name.name())
                    ? new RegionAccessPath(name.name(), "")
                    : null;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return regionAccessPath(indexed.receiver(), regionParameters);
        }
        if (expr instanceof Ast.MemberExpr member) {
            RegionAccessPath base = regionAccessPath(member.receiver(), regionParameters);
            if (base == null) return null;
            String field = base.field().isEmpty()
                    ? member.member()
                    : base.field() + "." + member.member();
            return new RegionAccessPath(base.root(), field);
        }
        return null;
    }

    private static boolean effectCovers(String declaredField, String actualField) {
        if (declaredField == null || declaredField.isEmpty()) return true;
        return declaredField.equals(actualField)
                || (!actualField.isEmpty() && actualField.startsWith(declaredField + "."));
    }

    private static void requireRegionPermission(
            RegionAccessPath path,
            ComputeScope scope,
            boolean write) {
        boolean allowed = false;
        for (RegionEffect effect : scope.effects()) {
            if (!effect.rootParameter().equals(path.root())) continue;
            if (!effectCovers(effect.fieldPath(), path.field())) continue;
            if (write) {
                if (effect.privilege().equals("writes")
                        || effect.privilege().equals("discards")) {
                    allowed = true;
                    break;
                }
            } else if (effect.privilege().equals("reads")
                    || effect.privilege().equals("writes")) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw unsafe(
                    scope.function().name(),
                    (write ? "write" : "read")
                            + " of undeclared region footprint '"
                            + path.root()
                            + (path.field().isEmpty() ? "" : "." + path.field())
                            + "'");
        }
    }

    private void validateComputeCall(
            Ast.CallExpr call,
            ComputeScope scope,
            Set<String> locals) {
        if (call.callee() instanceof Ast.NameExpr direct) {
            if (locals.contains(direct.name())) {
                throw unsafe(
                        scope.function().name(),
                        "dynamic/local callable '" + direct.name() + "'");
            }
            if (PURE_INTRINSICS.contains(direct.name())) {
                for (Ast.Expr argument : call.arguments()) {
                    validateComputeExpr(argument, scope, locals);
                }
                return;
            }

            ResolvedFunction target = resolveFunctionRef(scope.module(), direct.name());
            if (target != null && isCompute(target.declaration())) {
                validateComputeCallArguments(call, target, scope, locals);
                computeCalls.computeIfAbsent(scope.functionId(), ignored -> new LinkedHashSet<>())
                        .add(target.id());
                return;
            }
            throw unsafe(
                    scope.function().name(),
                    "call to non-compute callable '" + direct.name() + "'");
        }

        if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            ResolvedFunction target = resolveFunctionRef(
                    scope.module(),
                    namespace.name() + "." + member.member());
            if (target != null && isCompute(target.declaration())) {
                validateComputeCallArguments(call, target, scope, locals);
                computeCalls.computeIfAbsent(scope.functionId(), ignored -> new LinkedHashSet<>())
                        .add(target.id());
                return;
            }
        }

        throw unsafe(
                scope.function().name(),
                "dynamic/member call; compute fnc calls must resolve statically "
                        + "to another compute fnc or pure intrinsic");
    }

    private void validateComputeCallArguments(
            Ast.CallExpr call,
            ResolvedFunction target,
            ComputeScope caller,
            Set<String> locals) {
        Ast.FunctionDecl callee = target.declaration();
        List<Ast.Param> parameters = callee.parameters();

        for (int index = 0; index < call.arguments().size(); index++) {
            Ast.Expr argument = call.arguments().get(index);
            Ast.Param parameter = parameters.get(index);
            Ast.TypeRef parameterType = parameter.type();
            boolean regionParameter = parameterType != null
                    && (parameterType.name().equals("Region")
                        || parameterType.name().equals("RegionView"));

            if (!regionParameter) {
                validateComputeExpr(argument, caller, locals);
                continue;
            }

            RegionAccessPath argumentPath = regionAccessPath(argument, caller.regionParameters());
            if (argumentPath == null) {
                throw unsafe(
                        caller.function().name(),
                        "region argument for compute fnc '" + target.id()
                                + "' must derive from a declared caller Region/RegionView parameter");
            }
            validateRegionIndexes(argument, caller, locals);
        }

        ComputeContract calleeContract = contractOf(callee);
        validateNestedComputePhysicalContract(call, target, calleeContract, caller);

        for (RegionEffect effect : calleeContract.effects()) {
            int parameterIndex = parameterIndex(callee.parameters(), effect.rootParameter());
            if (parameterIndex < 0 || parameterIndex >= call.arguments().size()) {
                throw new IllegalStateException(
                        "checked compute effect references missing callee parameter: "
                                + effect.rootParameter());
            }

            Ast.Expr argument = call.arguments().get(parameterIndex);
            RegionAccessPath base = regionAccessPath(argument, caller.regionParameters());
            if (base == null) {
                throw unsafe(
                        caller.function().name(),
                        "cannot map callee effect '" + effect.rootParameter()
                                + "' onto a caller region");
            }

            String mappedField;
            if (base.field().isEmpty()) {
                mappedField = effect.fieldPath();
            } else if (effect.fieldPath() == null || effect.fieldPath().isEmpty()) {
                mappedField = base.field();
            } else {
                mappedField = base.field() + "." + effect.fieldPath();
            }

            requireMappedCalleeEffect(
                    new RegionAccessPath(base.root(), mappedField),
                    effect,
                    caller);
        }
    }

    private static void validateNestedComputePhysicalContract(
            Ast.CallExpr call,
            ResolvedFunction target,
            ComputeContract calleeContract,
            ComputeScope caller) {
        ComputeContract callerContract = contractOf(caller.function());

        String calleePlacement = calleeContract.placement();
        String callerPlacement = callerContract.placement();
        if (!calleePlacement.equals("auto")) {
            if (callerPlacement.equals("auto")) {
                throw unsafe(
                        caller.function().name(),
                        "call to compute fnc '" + target.id()
                                + "' hides explicit place " + calleePlacement
                                + " inside place auto; declare the same explicit placement "
                                + "on the caller until transitive placement inference exists");
            }
            if (!callerPlacement.equals(calleePlacement)) {
                throw unsafe(
                        caller.function().name(),
                        "call to compute fnc '" + target.id()
                                + "' requires place " + calleePlacement
                                + " but caller declares place " + callerPlacement);
            }
        }

        for (Map.Entry<String, String> layout : calleeContract.layouts().entrySet()) {
            String requiredLayout = layout.getValue();
            if (requiredLayout.equals("auto")) continue;

            int parameterIndex = parameterIndex(
                    target.declaration().parameters(),
                    layout.getKey());
            if (parameterIndex < 0 || parameterIndex >= call.arguments().size()) {
                throw new IllegalStateException(
                        "checked compute layout references missing callee parameter: "
                                + layout.getKey());
            }

            RegionAccessPath mapped =
                    regionAccessPath(call.arguments().get(parameterIndex), caller.regionParameters());
            if (mapped == null || !mapped.field().isEmpty()) {
                throw unsafe(
                        caller.function().name(),
                        "cannot map nested layout requirement for compute fnc '"
                                + target.id() + "' to a caller region root");
            }

            String callerLayout = callerContract.layouts().get(mapped.root());
            if (callerLayout == null || callerLayout.equals("auto")) {
                throw unsafe(
                        caller.function().name(),
                        "call to compute fnc '" + target.id()
                                + "' hides explicit layout " + requiredLayout
                                + " for region '" + mapped.root()
                                + "'; declare the same layout on the caller "
                                + "until transitive layout inference exists");
            }
            if (!callerLayout.equals(requiredLayout)) {
                throw unsafe(
                        caller.function().name(),
                        "call to compute fnc '" + target.id()
                                + "' requires layout " + requiredLayout
                                + " for region '" + mapped.root()
                                + "' but caller declares " + callerLayout);
            }
        }
    }

    private static int parameterIndex(List<Ast.Param> parameters, String name) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    private static void requireMappedCalleeEffect(
            RegionAccessPath path,
            RegionEffect required,
            ComputeScope caller) {
        for (RegionEffect available : caller.effects()) {
            if (!available.rootParameter().equals(path.root())) continue;
            if (!effectCovers(available.fieldPath(), path.field())) continue;

            boolean allowed = switch (required.privilege()) {
                case "reads" -> available.privilege().equals("reads")
                        || available.privilege().equals("writes");
                case "writes" -> available.privilege().equals("writes");
                case "discards" -> available.privilege().equals("writes")
                        || available.privilege().equals("discards");
                case "reduces" -> available.privilege().equals("writes")
                        || (available.privilege().equals("reduces")
                            && Objects.equals(
                                    available.reductionOperator(),
                                    required.reductionOperator()));
                case "atomic" -> available.privilege().equals("writes")
                        || available.privilege().equals("atomic");
                default -> false;
            };
            if (allowed) return;
        }

        throw unsafe(
                caller.function().name(),
                "call to compute fnc requires undeclared "
                        + required.privilege()
                        + " effect on '"
                        + path.root()
                        + (path.field().isEmpty() ? "" : "." + path.field())
                        + "'");
    }

    private void validateComputeCallGraph() {
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String function : computeCalls.keySet()) {
            if (hasCycle(function, computeCalls, visiting, visited)) {
                throw new IllegalArgumentException(
                        "compute fnc call graph contains recursion involving '" + function
                                + "'; the initial device/AOT-safe subset requires finite acyclic calls");
            }
        }
    }

    private record ResolvedFunction(String id, Ast.FunctionDecl declaration) { }

    private void validateFlow(String module, Ast.FlowDecl flow) {
        if (flow.edges().isEmpty()) {
            throw new IllegalArgumentException(
                    "flow '" + flow.name() + "' requires at least one dependency edge");
        }
        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        LinkedHashSet<String> seenEdges = new LinkedHashSet<>();
        for (Ast.FlowEdge edge : flow.edges()) {
            ResolvedFunction from = resolveFunctionRef(module, edge.from());
            ResolvedFunction to = resolveFunctionRef(module, edge.to());
            if (from == null || !isCompute(from.declaration())) {
                throw new IllegalArgumentException(
                        "flow '" + flow.name() + "' source '" + edge.from() + "' is not a compute fnc");
            }
            if (to == null || !isCompute(to.declaration())) {
                throw new IllegalArgumentException(
                        "flow '" + flow.name() + "' target '" + edge.to() + "' is not a compute fnc");
            }
            String canonicalEdge = from.id() + "\u0000" + to.id();
            if (!seenEdges.add(canonicalEdge)) {
                throw new IllegalArgumentException(
                        "flow '" + flow.name() + "' contains duplicate dependency edge "
                                + edge.from() + " -> " + edge.to());
            }
            outgoing.computeIfAbsent(from.id(), ignored -> new LinkedHashSet<>()).add(to.id());
            outgoing.computeIfAbsent(to.id(), ignored -> new LinkedHashSet<>());
        }

        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String node : outgoing.keySet()) {
            if (hasCycle(node, outgoing, visiting, visited)) {
                throw new IllegalArgumentException("flow '" + flow.name() + "' contains a cycle");
            }
        }
    }

    private static boolean hasCycle(
            String node,
            Map<String, ? extends Set<String>> outgoing,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(node)) return false;
        if (!visiting.add(node)) return true;
        Set<String> nextNodes = outgoing.get(node);
        if (nextNodes != null) {
            for (String next : nextNodes) {
                if (hasCycle(next, outgoing, visiting, visited)) return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private void validateAspect(String module, Ast.AspectDecl aspect) {
        if (aspect.rules().isEmpty()) {
            throw new IllegalArgumentException(
                    "aspect '" + aspect.name() + "' requires at least one advice rule");
        }
        Set<String> rules = new LinkedHashSet<>();
        for (Ast.AspectRule rule : aspect.rules()) {
            Ast.FunctionDecl handler = resolveFunction(module, rule.handler());
            if (handler == null) {
                throw new IllegalArgumentException(
                        "aspect '" + aspect.name() + "' references unknown handler '" + rule.handler() + "'");
            }
            if (handler.kind() != Ast.CallableKind.FNC
                    || handler.actorKind() != Ast.ActorKind.NONE
                    || handler.async()
                    || !handler.genericParameters().isEmpty()
                    || !handler.parameters().isEmpty()
                    || handler.returnType() == null
                    || !handler.returnType().name().equals("void")) {
                throw new IllegalArgumentException(
                        "aspect handler '" + rule.handler()
                                + "' must be a synchronous zero-argument void, "
                                + "non-actor, non-generic fnc");
            }

            if (rule.adviceKind() == Ast.AspectAdviceKind.AROUND) {
                throw new IllegalArgumentException(
                        "around advice is reserved until Oreslang has an explicit statically typed "
                                + "proceed/continuation ABI; use before/after for now");
            }

            String key = rule.joinPoint() + ":" + rule.adviceKind() + ":" + rule.handler();
            if (!rules.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate aspect rule in '" + aspect.name() + "': " + key);
            }
        }
    }

    private Ast.FunctionDecl resolveFunction(String module, String name) {
        ResolvedFunction resolved = resolveFunctionRef(module, name);
        return resolved == null ? null : resolved.declaration();
    }

    private ResolvedFunction resolveFunctionRef(String module, String name) {
        if (name.contains(".")) {
            Ast.FunctionDecl qualified = functions.get(name);
            return qualified == null ? null : new ResolvedFunction(name, qualified);
        }

        String localId = qualify(module, name);
        Ast.FunctionDecl local = functions.get(localId);
        if (local != null) return new ResolvedFunction(localId, local);

        Ast.FunctionDecl target = resolveAnyFunction(name);
        if (target == null) return null;
        for (Map.Entry<String, Ast.FunctionDecl> entry : functions.entrySet()) {
            if (entry.getValue() == target) return new ResolvedFunction(entry.getKey(), target);
        }
        throw new IllegalStateException("indexed function has no canonical id: " + name);
    }

    private Ast.FunctionDecl resolveAnyFunction(String name) {
        if (name.contains(".")) return functions.get(name);
        if (ambiguousFunctions.contains(name)) {
            throw new IllegalArgumentException(
                    "ambiguous callable '" + name + "'; qualify it with its module");
        }
        return simpleFunctions.get(name);
    }

    private static IllegalArgumentException unsafe(String owner, String feature) {
        return new IllegalArgumentException(
                "compute fnc '" + owner + "' cannot use " + feature
                        + "; compute bodies must remain statically analyzable and device/AOT safe");
    }

    private static boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals(name)) return true;
        }
        return false;
    }

    private static String[] splitPath(String path) {
        int dot = path.indexOf('.');
        return dot < 0
                ? new String[] { path, "" }
                : new String[] { path.substring(0, dot), path.substring(dot + 1) };
    }

    private static String qualify(String module, String name) {
        return module.equals("__root__") ? name : module + "." + name;
    }
}
