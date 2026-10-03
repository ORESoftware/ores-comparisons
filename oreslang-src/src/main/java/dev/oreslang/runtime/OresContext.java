package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
    private final String tenantId;

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        this.tenantId = tenantIdFromApplicationArguments(env.getApplicationArguments());
        this.actors = new ActorRuntime(isolatePolicy);
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
    public String tenantId() { return tenantId; }

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

    public Map<String, Object> processDescriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("context_id", contextId.toString());
        if (tenantId != null) descriptor.put("tenant_id", tenantId);
        descriptor.put("runtime", "graalvm-truffle");
        descriptor.put("language", "oreslang");
        descriptor.put("execution_mode", executionProfile.mode().name());
        descriptor.put("platform", executionProfile.platform().name());
        descriptor.put("scheduler_safepoints", schedulerSafepoints.get());
        return Map.copyOf(descriptor);
    }

    private static String tenantIdFromApplicationArguments(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--ores-tenant-id=")) {
                String value = arg.substring("--ores-tenant-id=".length());
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    @Override
    public void close() {
        actors.close();
        output.flush();
    }
}
