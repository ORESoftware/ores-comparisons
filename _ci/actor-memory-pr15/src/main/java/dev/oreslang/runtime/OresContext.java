package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ActorRuntime actors;
    private final UUID contextId = UUID.randomUUID();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final IsolatePolicy isolatePolicy;
    private final ExecutionProfile executionProfile;
    private final ReentrantLock adversarialActorTurnLock = new ReentrantLock(true);

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        this.actors = new ActorRuntime(
                isolatePolicy,
                ActorRuntime.DispatcherConfig.defaults(),
                new ActorRuntime.TurnExecutor() {
                    @Override
                    public void execute(Runnable turn) {
                        executeActorTurn(null, isolatePolicy, turn);
                    }

                    @Override
                    public void execute(
                            ActorRuntime.ActorKind kind,
                            IsolatePolicy actorPolicy,
                            Runnable turn) {
                        executeActorTurn(kind, actorPolicy, turn);
                    }

                    @Override
                    public boolean supportsUntrustedIsolation() {
                        // This OresContext has one guest context. Even when the
                        // whole context is SandboxPolicy.UNTRUSTED, one hostile
                        // actor could consume that context's CPU budget before
                        // the sandbox terminates it. That is process containment,
                        // not per-actor starvation isolation. Keep untrusted
                        // actor admission closed until this executor maps each
                        // such actor to its own hard Graal/native isolate.
                        return false;
                    }
                });
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public ActorRuntime actors() { return actors; }
    public UUID contextId() { return contextId; }
    public IsolatePolicy isolatePolicy() { return isolatePolicy; }
    public ExecutionProfile executionProfile() { return executionProfile; }

    public void requireCapability(IsolatePolicy.Capability capability, String api) {
        isolatePolicy.require(capability, api);
    }

    /**
     * Compiler-injected cooperative scheduling checkpoint. Loops call this on
     * every iteration so a future supervisor/control mailbox can interrupt
     * long-running actor code without requiring recursion-only looping.
     */
    public void schedulerSafepoint() {
        schedulerSafepoints.incrementAndGet();
        actors.schedulerSafepoint();
    }

    public long schedulerSafepoints() { return schedulerSafepoints.get(); }

    private void executeActorTurn(
            ActorRuntime.ActorKind kind,
            IsolatePolicy actorPolicy,
            Runnable turn) {
        // A strict/adversarial Truffle context remains single-guest-thread at
        // this layer. ActorRuntime still uses separate ready queues/pools, but
        // this shared context is not advertised as per-actor hostile isolation.
        boolean serialize = isolatePolicy.adversarial();
        if (serialize) adversarialActorTurnLock.lock();
        TruffleContext truffleContext = env.getContext();
        Object previous = null;
        boolean entered = false;
        try {
            previous = truffleContext.enter(null);
            entered = true;
            turn.run();
        } finally {
            if (entered) truffleContext.leave(null, previous);
            if (serialize) adversarialActorTurnLock.unlock();
        }
    }


    public Map<String, Object> processDescriptor() {
        return Map.of(
                "context_id", contextId.toString(),
                "runtime", "graalvm-truffle",
                "language", "oreslang",
                "execution_mode", executionProfile.mode().name(),
                "platform", executionProfile.platform().name(),
                "scheduler_safepoints", schedulerSafepoints.get());
    }

    @Override
    public void close() {
        actors.close();
        output.flush();
    }
}
