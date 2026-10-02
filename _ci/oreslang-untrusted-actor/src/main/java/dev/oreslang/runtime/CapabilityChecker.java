package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Language-level capability admission pass. This executes before guest
 * statements and complements Graal/OS isolation rather than replacing it.
 */
public final class CapabilityChecker {
    private CapabilityChecker() { }

    public static void check(Ast.Program program, IsolatePolicy policy) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    IsolatePolicy actorPolicy = switch (fn.actorKind()) {
                        case PRIVATE -> policy.withoutCapabilities(
                                IsolatePolicy.Capability.SHARED_MEMORY,
                                IsolatePolicy.Capability.ACTOR_SHARE_READONLY);
                        case UNTRUSTED -> IsolatePolicy.untrustedActor();
                        case NONE, SHARED -> policy;
                    };
                    if (fn.actorKind() == Ast.ActorKind.SHARED) {
                        require(actorPolicy, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor fnc " + fn.name());
                    }
                    checkCallableTypes(fn.parameters(), fn.returnType(), actorPolicy);
                    checkStatements(fn.body(), actorPolicy);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    IsolatePolicy actorPolicy = switch (klass.actorKind()) {
                        case PRIVATE -> policy.withoutCapabilities(
                                IsolatePolicy.Capability.SHARED_MEMORY,
                                IsolatePolicy.Capability.ACTOR_SHARE_READONLY);
                        case UNTRUSTED -> IsolatePolicy.untrustedActor();
                        case NONE, SHARED -> policy;
                    };
                    if (klass.actorKind() == Ast.ActorKind.SHARED) {
                        require(actorPolicy, IsolatePolicy.Capability.SHARED_MEMORY, "shared actor " + klass.name());
                    }
                    for (Ast.TypeRef parent : klass.parents()) checkType(parent, actorPolicy);
                    for (Ast.TypeRef iface : klass.interfaces()) checkType(iface, actorPolicy);
                    for (Ast.FieldDecl field : klass.fields()) {
                        checkType(field.type(), actorPolicy);
                        if (field.initializer() != null) checkExpr(field.initializer(), actorPolicy);
                    }
                    for (Ast.MethodDecl method : klass.methods()) {
                        checkType(method.explicitReceiverType(), actorPolicy);
                        checkCallableTypes(method.parameters(), method.returnType(), actorPolicy);
                        checkStatements(method.body(), actorPolicy);
                    }
                } else if (declaration instanceof Ast.InterfaceDecl iface) {
                    for (Ast.TypeRef parent : iface.parents()) checkType(parent, policy);
                    for (Ast.InterfaceMember member : iface.members()) {
                        if (member instanceof Ast.InterfaceFunctionDecl fn) {
                            checkCallableTypes(fn.parameters(), fn.returnType(), policy);
                        } else if (member instanceof Ast.InterfaceFieldDecl field) {
                            checkType(field.type(), policy);
                        }
                    }
                } else if (declaration instanceof Ast.FieldDecl field) {
                    checkType(field.type(), policy);
                    if (field.initializer() != null) checkExpr(field.initializer(), policy);
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    checkType(alias.target(), policy);
                }
            }
        }
        checkUntrustedTransitiveEffects(program);
    }

    private static void checkCallableTypes(
            List<Ast.Param> parameters,
            Ast.TypeRef returnType,
            IsolatePolicy policy) {
        for (Ast.Param parameter : parameters) checkType(parameter.type(), policy);
        checkType(returnType, policy);
    }

    private static void checkType(Ast.TypeRef type, IsolatePolicy policy) {
        if (type == null) return;
        if (type.name().equals("SharedMutex")) {
            require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex<T>");
        }
        for (Ast.TypeRef argument : type.arguments()) checkType(argument, policy);
    }

    private static void checkStatements(List<Ast.Stmt> statements, IsolatePolicy policy) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) {
                checkType(s.declaredType(), policy);
                checkExpr(s.initializer(), policy);
            }
            else if (stmt instanceof Ast.DestructureStmt s) checkExpr(s.initializer(), policy);
            else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) checkExpr(s.value(), policy);
            else if (stmt instanceof Ast.ExprStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.DeferStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    checkExpr(b.condition(), policy);
                    checkStatements(b.body(), policy);
                }
                checkStatements(s.elseBody(), policy);
            } else if (stmt instanceof Ast.TryStmt s) {
                checkStatements(s.body(), policy);
                checkStatements(s.catchBody(), policy);
                checkStatements(s.finallyBody(), policy);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                checkExpr(s.iterable(), policy);
                checkStatements(s.body(), policy);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() != null) checkStatements(List.of(s.initializer()), policy);
                if (s.condition() != null) checkExpr(s.condition(), policy);
                if (s.update() != null) checkExpr(s.update(), policy);
                checkStatements(s.body(), policy);
            }
        }
    }

    private static void checkExpr(Ast.Expr expr, IsolatePolicy policy) {
        if (expr instanceof Ast.NameExpr n && isZeroAuthorityAdversarial(policy)
                && isRestrictedFacadeRoot(n.name())) {
            throw new SecurityException(
                    "untrusted actor cannot extract restricted capability facade '" + n.name() + "'");
        }
        if (expr instanceof Ast.NameExpr n && n.name().equals("print")) {
            require(policy, IsolatePolicy.Capability.STDOUT, "print");
        } else if (expr instanceof Ast.NameExpr n && n.name().equals("SharedMutex")) {
            require(policy, IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex");
        }
        else if (expr instanceof Ast.CallExpr c) {
            checkExpr(c.callee(), policy);
            for (Ast.Expr arg : c.arguments()) checkExpr(arg, policy);
        } else if (expr instanceof Ast.MemberExpr m) {
            String path = memberPath(m);
            if (path != null) {
                if (path.startsWith("stdio.") || path.equals("stdio")) require(policy, IsolatePolicy.Capability.STDOUT, path);
                if (path.startsWith("process.descriptor") || path.equals("process.context_id")) require(policy, IsolatePolicy.Capability.PROCESS_INFO, path);
                if (path.startsWith("process.share_readonly")) require(policy, IsolatePolicy.Capability.ACTOR_SHARE_READONLY, path);
                if (path.equals("SharedMutex") || path.startsWith("SharedMutex.")) require(policy, IsolatePolicy.Capability.SHARED_MEMORY, path);
                if (path.startsWith("actor.")) require(policy, IsolatePolicy.Capability.ACTOR_SPAWN, path);
                if (path.startsWith("network.")) require(policy, IsolatePolicy.Capability.NETWORK, path);
                if (path.startsWith("ipc.")) require(policy, IsolatePolicy.Capability.IPC, path);
                if (path.startsWith("gpu.")) require(policy, IsolatePolicy.Capability.GPU, path);
                if (path.startsWith("fs.read")) require(policy, IsolatePolicy.Capability.FILESYSTEM_READ, path);
                if (path.startsWith("fs.write")) require(policy, IsolatePolicy.Capability.FILESYSTEM_WRITE, path);
                if (path.startsWith("env.")) require(policy, IsolatePolicy.Capability.ENVIRONMENT, path);
                if (path.startsWith("ffi.")) require(policy, IsolatePolicy.Capability.FFI, path);
                if (path.startsWith("polyglot.")) require(policy, IsolatePolicy.Capability.POLYGLOT, path);
                if (path.startsWith("thread.")) require(policy, IsolatePolicy.Capability.THREAD_CREATE, path);
                if (path.startsWith("process.spawn")) require(policy, IsolatePolicy.Capability.CHILD_PROCESS, path);
            }
            checkExpr(m.receiver(), policy);
        } else if (expr instanceof Ast.BinaryExpr e) { checkExpr(e.left(), policy); checkExpr(e.right(), policy); }
        else if (expr instanceof Ast.UnaryExpr e) checkExpr(e.operand(), policy);
        else if (expr instanceof Ast.AssignExpr e) checkExpr(e.value(), policy);
        else if (expr instanceof Ast.ConditionalExpr e) { checkExpr(e.condition(), policy); checkExpr(e.whenTrue(), policy); checkExpr(e.whenFalse(), policy); }
        else if (expr instanceof Ast.IndexExpr e) { checkExpr(e.receiver(), policy); checkExpr(e.index(), policy); }
        else if (expr instanceof Ast.NewExpr e) {
            checkType(e.type(), policy);
            for (Ast.Expr a : e.arguments()) checkExpr(a, policy);
        }
        else if (expr instanceof Ast.AwaitExpr e) checkExpr(e.expression(), policy);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField f : e.fields()) checkExpr(f.value(), policy);
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) checkExpr(e.expressionBody(), policy);
            if (e.blockBody() != null) checkStatements(e.blockBody(), policy);
        }
    }


    /**
     * Fail-closed transitive admission for local helper functions reachable
     * from an untrusted actor. Imported code has no effect signature yet, so
     * untrusted actors may not invoke imports until explicit effect metadata is
     * part of the import contract.
     */
    private static void checkUntrustedTransitiveEffects(Ast.Program program) {
        Map<String, Ast.FunctionDecl> functions = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)) continue;
                functions.put(module.name() + "." + fn.name(), fn);
                Ast.FunctionDecl previous = functions.putIfAbsent(fn.name(), fn);
                if (previous != null && previous != fn) {
                    ambiguous.add(fn.name());
                    functions.remove(fn.name());
                }
            }
        }

        Set<String> importedRoots = new HashSet<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.wildcard()) importedRoots.add(imported.namespace());
            else importedRoots.addAll(imported.names());
        }

        Set<Ast.FunctionDecl> visited =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn
                        && fn.actorKind() == Ast.ActorKind.UNTRUSTED) {
                    checkUntrustedHelperCalls(
                            fn.body(), functions, ambiguous, importedRoots, visited);
                } else if (declaration instanceof Ast.ClassDecl klass
                        && klass.actorKind() == Ast.ActorKind.UNTRUSTED) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        checkUntrustedHelperCalls(
                                method.body(), functions, ambiguous, importedRoots, visited);
                    }
                }
            }
        }
    }

    private static void checkUntrustedHelperCalls(
            List<Ast.Stmt> statements,
            Map<String, Ast.FunctionDecl> functions,
            Set<String> ambiguous,
            Set<String> importedRoots,
            Set<Ast.FunctionDecl> visited) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) {
                checkUntrustedHelperExpr(s.initializer(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.DestructureStmt s) {
                checkUntrustedHelperExpr(s.initializer(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) {
                checkUntrustedHelperExpr(s.value(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.ExprStmt s) {
                checkUntrustedHelperExpr(s.expression(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.DeferStmt s) {
                checkUntrustedHelperExpr(s.expression(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    checkUntrustedHelperExpr(branch.condition(), functions, ambiguous, importedRoots, visited);
                    checkUntrustedHelperCalls(branch.body(), functions, ambiguous, importedRoots, visited);
                }
                checkUntrustedHelperCalls(s.elseBody(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.TryStmt s) {
                checkUntrustedHelperCalls(s.body(), functions, ambiguous, importedRoots, visited);
                checkUntrustedHelperCalls(s.catchBody(), functions, ambiguous, importedRoots, visited);
                checkUntrustedHelperCalls(s.finallyBody(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                checkUntrustedHelperExpr(s.iterable(), functions, ambiguous, importedRoots, visited);
                checkUntrustedHelperCalls(s.body(), functions, ambiguous, importedRoots, visited);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() != null) {
                    checkUntrustedHelperCalls(List.of(s.initializer()), functions, ambiguous, importedRoots, visited);
                }
                if (s.condition() != null) {
                    checkUntrustedHelperExpr(s.condition(), functions, ambiguous, importedRoots, visited);
                }
                if (s.update() != null) {
                    checkUntrustedHelperExpr(s.update(), functions, ambiguous, importedRoots, visited);
                }
                checkUntrustedHelperCalls(s.body(), functions, ambiguous, importedRoots, visited);
            }
        }
    }

    private static void checkUntrustedHelperExpr(
            Ast.Expr expr,
            Map<String, Ast.FunctionDecl> functions,
            Set<String> ambiguous,
            Set<String> importedRoots,
            Set<Ast.FunctionDecl> visited) {
        if (expr instanceof Ast.NameExpr name) {
            if (importedRoots.contains(name.name())) {
                throw new SecurityException(
                        "untrusted actor cannot extract imported code without explicit effect metadata: "
                                + name.name());
            }
            Ast.FunctionDecl helper = ambiguous.contains(name.name())
                    ? null
                    : functions.get(name.name());
            if (helper != null && visited.add(helper)) {
                if (helper.actorKind() != Ast.ActorKind.NONE) {
                    require(
                            IsolatePolicy.untrustedActor(),
                            IsolatePolicy.Capability.ACTOR_SPAWN,
                            "actor helper " + name.name());
                }
                checkCallableTypes(
                        helper.parameters(), helper.returnType(), IsolatePolicy.untrustedActor());
                checkStatements(helper.body(), IsolatePolicy.untrustedActor());
                checkUntrustedHelperCalls(
                        helper.body(), functions, ambiguous, importedRoots, visited);
            }
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            String path = memberPath(call.callee());
            if (path != null) {
                String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                if (importedRoots.contains(root)) {
                    throw new SecurityException(
                            "untrusted actor cannot call imported code without explicit effect metadata: " + path);
                }
                Ast.FunctionDecl helper = ambiguous.contains(path) ? null : functions.get(path);
                if (helper != null) {
                    if (helper.actorKind() != Ast.ActorKind.NONE) {
                        require(
                                IsolatePolicy.untrustedActor(),
                                IsolatePolicy.Capability.ACTOR_SPAWN,
                                "actor helper " + path);
                    }
                    if (visited.add(helper)) {
                        checkCallableTypes(
                                helper.parameters(), helper.returnType(), IsolatePolicy.untrustedActor());
                        checkStatements(helper.body(), IsolatePolicy.untrustedActor());
                        checkUntrustedHelperCalls(
                                helper.body(), functions, ambiguous, importedRoots, visited);
                    }
                }
            }
            checkUntrustedHelperExpr(call.callee(), functions, ambiguous, importedRoots, visited);
            for (Ast.Expr arg : call.arguments()) {
                checkUntrustedHelperExpr(arg, functions, ambiguous, importedRoots, visited);
            }
        } else if (expr instanceof Ast.MemberExpr e) {
            checkUntrustedHelperExpr(e.receiver(), functions, ambiguous, importedRoots, visited);
        } else if (expr instanceof Ast.BinaryExpr e) {
            checkUntrustedHelperExpr(e.left(), functions, ambiguous, importedRoots, visited);
            checkUntrustedHelperExpr(e.right(), functions, ambiguous, importedRoots, visited);
        } else if (expr instanceof Ast.UnaryExpr e) {
            checkUntrustedHelperExpr(e.operand(), functions, ambiguous, importedRoots, visited);
        } else if (expr instanceof Ast.AssignExpr e) {
            checkUntrustedHelperExpr(e.value(), functions, ambiguous, importedRoots, visited);
        } else if (expr instanceof Ast.ConditionalExpr e) {
            checkUntrustedHelperExpr(e.condition(), functions, ambiguous, importedRoots, visited);
            checkUntrustedHelperExpr(e.whenTrue(), functions, ambiguous, importedRoots, visited);
            checkUntrustedHelperExpr(e.whenFalse(), functions, ambiguous, importedRoots, visited);
        } else if (expr instanceof Ast.IndexExpr e) {
            checkUntrustedHelperExpr(e.receiver(), functions, ambiguous, importedRoots, visited);
            checkUntrustedHelperExpr(e.index(), functions, ambiguous, importedRoots, visited);
        } else if (expr instanceof Ast.NewExpr e) {
            for (Ast.Expr arg : e.arguments()) {
                checkUntrustedHelperExpr(arg, functions, ambiguous, importedRoots, visited);
            }
        } else if (expr instanceof Ast.AwaitExpr e) {
            checkUntrustedHelperExpr(e.expression(), functions, ambiguous, importedRoots, visited);
        } else if (expr instanceof Ast.ListExpr e) {
            for (Ast.Expr item : e.elements()) {
                checkUntrustedHelperExpr(item, functions, ambiguous, importedRoots, visited);
            }
        } else if (expr instanceof Ast.TupleExpr e) {
            for (Ast.Expr item : e.elements()) {
                checkUntrustedHelperExpr(item, functions, ambiguous, importedRoots, visited);
            }
        } else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) {
                checkUntrustedHelperExpr(field.value(), functions, ambiguous, importedRoots, visited);
            }
        } else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) {
                checkUntrustedHelperExpr(
                        e.expressionBody(), functions, ambiguous, importedRoots, visited);
            }
            if (e.blockBody() != null) {
                checkUntrustedHelperCalls(
                        e.blockBody(), functions, ambiguous, importedRoots, visited);
            }
        }
    }

    private static boolean isZeroAuthorityAdversarial(IsolatePolicy policy) {
        return policy.adversarial() && policy.capabilities().isEmpty();
    }

    private static boolean isRestrictedFacadeRoot(String name) {
        return switch (name) {
            case "stdio", "process", "actor", "network", "ipc", "gpu",
                    "fs", "env", "ffi", "polyglot", "thread", "SharedMutex", "print" -> true;
            default -> false;
        };
    }

    private static String memberPath(Ast.Expr expr) {
        if (expr instanceof Ast.NameExpr n) return n.name();
        if (expr instanceof Ast.MemberExpr m) {
            String parent = memberPath(m.receiver());
            return parent == null ? null : parent + "." + m.member();
        }
        return null;
    }

    private static void require(IsolatePolicy policy, IsolatePolicy.Capability capability, String api) {
        if (!policy.allows(capability)) throw new SecurityException("Oreslang isolate denies capability " + capability + " required by " + api);
    }
}
