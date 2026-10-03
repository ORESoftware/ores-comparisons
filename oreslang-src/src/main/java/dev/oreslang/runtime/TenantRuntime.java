package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-owned tenant boundary.
 *
 * One TenantRuntime owns one isolated Graal Engine. Every Oreslang Context
 * opened through it therefore executes in the same tenant isolate heap, GC,
 * and JIT domain. ActorRuntime instances inside those contexts may then choose
 * SHARED_HEAP or PRIVATE_ARENA independently per actor.
 */
public final class TenantRuntime implements AutoCloseable {
    private final String tenantId;
    private final IsolatePolicy policy;
    private final ExecutionProfile executionProfile;
    private final Engine engine;
    private final AtomicBoolean closed = new AtomicBoolean();

    public TenantRuntime(String tenantId, IsolatePolicy policy, ExecutionProfile executionProfile) {
        this.tenantId = validateTenantId(tenantId);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executionProfile = Objects.requireNonNull(executionProfile, "executionProfile");
        this.engine = policy.isolatedEngineBuilder().build();
    }

    public String tenantId() { return tenantId; }
    public IsolatePolicy policy() { return policy; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public Engine engine() { return engine; }

    /**
     * Opens a new guest context inside this tenant's existing isolate Engine.
     * Fresh contexts are useful for code generations while retaining one
     * physical tenant heap boundary.
     */
    public Context openContext() {
        if (closed.get()) throw new IllegalStateException("tenant runtime is closed");
        String[] baseArgs = policy.applicationArguments(executionProfile);
        String[] tenantArgs = Arrays.copyOf(baseArgs, baseArgs.length + 1);
        tenantArgs[baseArgs.length] = "--ores-tenant-id=" + tenantId;

        return policy.restrictedContextBuilder(executionProfile, engine)
                .arguments(OresLanguage.ID, tenantArgs)
                .build();
    }

    public HotReloadManager hotReloadManager() {
        return new HotReloadManager(policy, executionProfile, this::openContext);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) engine.close(true);
    }

    private static String validateTenantId(String value) {
        Objects.requireNonNull(value, "tenantId");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("tenantId must not be blank");
        if (trimmed.length() > 128) throw new IllegalArgumentException("tenantId is too long");
        if (!trimmed.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("tenantId contains unsupported characters");
        }
        return trimmed;
    }
}
