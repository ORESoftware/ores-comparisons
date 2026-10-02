package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Language-level capability admission pass. This executes before guest
 * statements and complements Graal/OS/runtime isolation rather than replacing it.
 *
 * <p>Private actors are deliberately checked under a stricter derived policy:
 * neither synchronized shared memory nor runtime-owned readonly sharing may be
 * created/dereferenced from a private actor turn. The ActorRuntime enforces the
 * same rule again at execution time.</p>
 */
public final class CapabilityChecker {
    private final Ast.Program program;
    private final IsolatePolicy rootPolicy;
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();

    private CapabilityChecker(Ast.Program program, IsolatePolicy rootPolicy) {
        this.program = program;
        this.rootPolicy = rootPolicy;
        collectSymbols();
    }

    public static void check(Ast.Program program, IsolatePolicy policy) {
        new CapabilityChecker(program, policy).validate();
    }

    private void collectSymbols() {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    index(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                }
            }
        }
    }

    private static <T> void index(
            Map<String, T> values,
            Set<String> ambiguous,
            String module,
            String name,
            T value) {
        values.put(module + "." + name, value);
        T previous = values.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            values.remove(name);
        }
    }

    private void validate() {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    checkFunction(
                            fn,
                            actorPolicy(rootPolicy, fn.actorKind()),
                            identityFunctionSet());
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    checkClass(klass, actorPolicy(rootPolicy, klass.actorKind()));
                } else if (declaration instanceof Ast.InterfaceDecl iface) {
                    Set<String> generics = Set.copyOf(iface.genericParameters());
                    for (Ast.TypeRef parent : iface.parents()) checkType(parent, rootPolicy, generics);
                    for (Ast.InterfaceMember member : iface.members()) {
                        if (member instanceof Ast.InterfaceFunctionDecl fn) {
                            Set<String> methodGenerics = union(generics, fn.genericParameters());
                            checkCallableTypes(fn.parameters(), fn.returnType(), rootPolicy, methodGenerics);
                        } else if (member instanceof Ast.InterfaceFieldDecl field) {
                            checkType(field.type(), rootPolicy, generics);
                        }
                    }
                } else if (declaration instanceof Ast.FieldDecl field) {
                    checkType(field.type(), rootPolicy, Set.of());
                    if (field.initializer() != null) {
                        checkExpr(field.initializer(), rootPolicy, Set.of(), identityFunctionSet());
                    }
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    checkType(alias.target(), rootPolicy, Set.copyOf(alias.genericParameters()));
                }
            }
        }
    }

    private IsolatePolicy actorPolicy(IsolatePolicy parent, Ast.ActorKind kind) {
        if (kind == Ast.ActorKind.PRIVATE) {
            return parent.withoutCapabilities(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY);
        }
        if (kind == Ast.ActorKind.SHARED) {
            require(parent, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor");
        }
        return parent;
    }

    private void checkFunction(
            Ast.FunctionDecl fn,
            IsolatePolicy policy,
            Set<Ast.FunctionDecl> callStack) {
        if (!callStack.add(fn)) return;
        try {
            if (fn.actorKind() == Ast.ActorKind.SHARED) {
                require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor fnc " + fn.name());
            }
            Set<String> generics = Set.copyOf(fn.genericParameters());
            checkCallableTypes(fn.parameters(), fn.returnType(), policy, generics);
            checkStatements(fn.body(), policy, generics, callStack);
        } finally {
            callStack.remove(fn);
        }
    }

    private void checkClass(Ast.ClassDecl klass, IsolatePolicy policy) {
        if (klass.actorKind() == Ast.ActorKind.SHARED) {
            require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor " + klass.name());
        }

        Set<String> classGenerics = Set.copyOf(klass.genericParameters());
        for (Ast.TypeRef parent : klass.parents()) checkType(parent, policy, classGenerics);
        for (Ast.TypeRef iface : klass.interfaces()) checkType(iface, policy, classGenerics);

        for (Ast.FieldDecl field : klass.fields()) {
            checkType(field.type(), policy, classGenerics);
            if (field.initializer() != null) {
                checkExpr(field.initializer(), policy, classGenerics, identityFunctionSet());
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Set<String> methodGenerics = union(classGenerics, method.genericParameters());
            checkType(method.explicitReceiverType(), policy, methodGenerics);
            checkCallableTypes(method.parameters(), method.returnType(), policy, methodGenerics);
            checkStatements(method.body(), policy, methodGenerics, identityFunctionSet());
        }
    }

    private void checkCallableTypes(
            List<Ast.Param> parameters,
            Ast.TypeRef returnType,
            IsolatePolicy policy,
            Set<String> generics) {
        for (Ast.Param parameter : parameters) checkType(parameter.type(), policy, generics);
        checkType(returnType, policy, generics);
    }

    /**
     * Checks the complete state-type closure, not just the spelling at the use
     * site. This prevents aliases and wrapper classes from hiding SharedMutex.
     */
    private void checkType(Ast.TypeRef type, IsolatePolicy policy, Set<String> generics) {
        checkType(
                type,
                policy,
                generics,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private void checkType(
            Ast.TypeRef type,
            IsolatePolicy policy,
            Set<String> generics,
            Set<Ast.TypeAliasDecl> aliasStack,
            Set<Ast.ClassDecl> classStack) {
        if (type == null) return;

        if (type.name().equals("SharedMutex")) {
            require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex<T>");
        }

        for (Ast.TypeRef argument : type.arguments()) {
            checkType(argument, policy, generics, aliasStack, classStack);
        }

        if (generics.contains(type.name())) return;

        Ast.TypeAliasDecl alias = findTypeAlias(type.name());
        if (alias != null && aliasStack.add(alias)) {
            try {
                checkType(
                        alias.target(),
                        policy,
                        union(generics, alias.genericParameters()),
                        aliasStack,
                        classStack);
            } finally {
                aliasStack.remove(alias);
            }
        }

        Ast.ClassDecl klass = findClass(type.name());
        if (klass != null && classStack.add(klass)) {
            try {
                Set<String> classGenerics = union(generics, klass.genericParameters());
                for (Ast.TypeRef parent : klass.parents()) {
                    checkType(parent, policy, classGenerics, aliasStack, classStack);
                }
                for (Ast.TypeRef iface : klass.interfaces()) {
                    checkType(iface, policy, classGenerics, aliasStack, classStack);
                }
                for (Ast.FieldDecl field : klass.fields()) {
                    checkType(field.type(), policy, classGenerics, aliasStack, classStack);
                }
            } finally {
                classStack.remove(klass);
            }
        }
    }

    private void checkStatements(
            List<Ast.Stmt> statements,
            IsolatePolicy policy,
            Set<String> generics,
            Set<Ast.FunctionDecl> callStack) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) {
                checkType(s.declaredType(), policy, generics);
                checkExpr(s.initializer(), policy, generics, callStack);
            } else if (stmt instanceof Ast.DestructureStmt s) {
                checkExpr(s.initializer(), policy, generics, callStack);
            } else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) {
                checkExpr(s.value(), policy, generics, callStack);
            } else if (stmt instanceof Ast.ExprStmt s) {
                checkExpr(s.expression(), policy, generics, callStack);
            } else if (stmt instanceof Ast.DeferStmt s) {
                checkExpr(s.expression(), policy, generics, callStack);
            } else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    checkExpr(b.condition(), policy, generics, callStack);
                    checkStatements(b.body(), policy, generics, callStack);
                }
                checkStatements(s.elseBody(), policy, generics, callStack);
            } else if (stmt instanceof Ast.TryStmt s) {
                checkStatements(s.body(), policy, generics, callStack);
                checkStatements(s.catchBody(), policy, generics, callStack);
                checkStatements(s.finallyBody(), policy, generics, callStack);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                checkExpr(s.iterable(), policy, generics, callStack);
                checkStatements(s.body(), policy, generics, callStack);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() != null) {
                    checkStatements(List.of(s.initializer()), policy, generics, callStack);
                }
                if (s.condition() != null) checkExpr(s.condition(), policy, generics, callStack);
                if (s.update() != null) checkExpr(s.update(), policy, generics, callStack);
                checkStatements(s.body(), policy, generics, callStack);
            }
        }
    }

    private void checkExpr(
            Ast.Expr expr,
            IsolatePolicy policy,
            Set<String> generics,
            Set<Ast.FunctionDecl> callStack) {
        if (expr == null) return;

        if (expr instanceof Ast.NameExpr n && n.name().equals("print")) {
            require(policy, IsolatePolicy.Capability.STDOUT, "print");
        } else if (expr instanceof Ast.NameExpr n && n.name().equals("SharedMutex")) {
            require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex");
        } else if (expr instanceof Ast.CallExpr c) {
            checkExpr(c.callee(), policy, generics, callStack);
            for (Ast.Expr arg : c.arguments()) checkExpr(arg, policy, generics, callStack);

            Ast.FunctionDecl direct = resolveDirectFunction(c.callee());
            if (direct != null) {
                IsolatePolicy calleePolicy = actorPolicy(policy, direct.actorKind());
                checkFunction(direct, calleePolicy, callStack);
            }
        } else if (expr instanceof Ast.MemberExpr m) {
            String path = memberPath(m);
            if (path != null) {
                if (path.startsWith("stdio.") || path.equals("stdio")) {
                    require(policy, IsolatePolicy.Capability.STDOUT, path);
                }
                if (path.startsWith("process.descriptor") || path.equals("process.context_id")) {
                    require(policy, IsolatePolicy.Capability.PROCESS_INFO, path);
                }
                if (path.startsWith("process.share_readonly")) {
                    require(policy, IsolatePolicy.Capability.ACTOR_SHARE_READONLY, path);
                }
                if (path.equals("SharedMutex") || path.startsWith("SharedMutex.")) {
                    require(policy, IsolatePolicy.Capability.SHARED_MEMORY, path);
                }
                if (path.startsWith("network.")) {
                    require(policy, IsolatePolicy.Capability.NETWORK, path);
                }
                if (path.startsWith("fs.read")) {
                    require(policy, IsolatePolicy.Capability.FILESYSTEM_READ, path);
                }
                if (path.startsWith("fs.write")) {
                    require(policy, IsolatePolicy.Capability.FILESYSTEM_WRITE, path);
                }
                if (path.startsWith("env.")) {
                    require(policy, IsolatePolicy.Capability.ENVIRONMENT, path);
                }
                if (path.startsWith("ffi.")) {
                    require(policy, IsolatePolicy.Capability.FFI, path);
                }
                if (path.startsWith("polyglot.")) {
                    require(policy, IsolatePolicy.Capability.POLYGLOT, path);
                }
                if (path.startsWith("thread.")) {
                    require(policy, IsolatePolicy.Capability.THREAD_CREATE, path);
                }
                if (path.startsWith("process.spawn")) {
                    require(policy, IsolatePolicy.Capability.CHILD_PROCESS, path);
                }
            }
            checkExpr(m.receiver(), policy, generics, callStack);
        } else if (expr instanceof Ast.BinaryExpr e) {
            checkExpr(e.left(), policy, generics, callStack);
            checkExpr(e.right(), policy, generics, callStack);
        } else if (expr instanceof Ast.UnaryExpr e) {
            checkExpr(e.operand(), policy, generics, callStack);
        } else if (expr instanceof Ast.AssignExpr e) {
            checkExpr(e.target(), policy, generics, callStack);
            checkExpr(e.value(), policy, generics, callStack);
        } else if (expr instanceof Ast.ConditionalExpr e) {
            checkExpr(e.condition(), policy, generics, callStack);
            checkExpr(e.whenTrue(), policy, generics, callStack);
            checkExpr(e.whenFalse(), policy, generics, callStack);
        } else if (expr instanceof Ast.IndexExpr e) {
            checkExpr(e.receiver(), policy, generics, callStack);
            checkExpr(e.index(), policy, generics, callStack);
        } else if (expr instanceof Ast.NewExpr e) {
            checkType(e.type(), policy, generics);
            for (Ast.Expr a : e.arguments()) checkExpr(a, policy, generics, callStack);
        } else if (expr instanceof Ast.AwaitExpr e) {
            checkExpr(e.expression(), policy, generics, callStack);
        } else if (expr instanceof Ast.ListExpr e) {
            for (Ast.Expr a : e.elements()) checkExpr(a, policy, generics, callStack);
        } else if (expr instanceof Ast.TupleExpr e) {
            for (Ast.Expr a : e.elements()) checkExpr(a, policy, generics, callStack);
        } else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField f : e.fields()) checkExpr(f.value(), policy, generics, callStack);
        } else if (expr instanceof Ast.LambdaExpr e) {
            Set<String> lambdaGenerics = generics;
            for (Ast.Param parameter : e.parameters()) {
                checkType(parameter.type(), policy, lambdaGenerics);
            }
            if (e.expressionBody() != null) {
                checkExpr(e.expressionBody(), policy, lambdaGenerics, callStack);
            }
            if (e.blockBody() != null) {
                checkStatements(e.blockBody(), policy, lambdaGenerics, callStack);
            }
        }
    }

    private Ast.FunctionDecl resolveDirectFunction(Ast.Expr callee) {
        if (callee instanceof Ast.NameExpr name) return findFunction(name.name());
        if (callee instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            return functions.get(namespace.name() + "." + member.member());
        }
        return null;
    }

    private Ast.FunctionDecl findFunction(String name) {
        return ambiguousFunctions.contains(name) ? null : functions.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        return ambiguousTypeAliases.contains(name) ? null : typeAliases.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        return ambiguousClasses.contains(name) ? null : classes.get(name);
    }

    private static Set<Ast.FunctionDecl> identityFunctionSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<String> union(Set<String> base, List<String> additions) {
        if (additions.isEmpty()) return base;
        HashSet<String> result = new HashSet<>(base);
        result.addAll(additions);
        return Set.copyOf(result);
    }

    private static String memberPath(Ast.Expr expr) {
        if (expr instanceof Ast.NameExpr n) return n.name();
        if (expr instanceof Ast.MemberExpr m) {
            String parent = memberPath(m.receiver());
            return parent == null ? null : parent + "." + m.member();
        }
        return null;
    }

    private static void require(
            IsolatePolicy policy,
            IsolatePolicy.Capability capability,
            String api) {
        if (!policy.allows(capability)) {
            throw new SecurityException(
                    "Oreslang isolate denies capability " + capability + " required by " + api);
        }
    }
}
