package dev.oreslang.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import jdk.management.VirtualThreadSchedulerMXBean;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang actor state is owned by one actor. Messages pass through freeze(),
 * which only accepts values that can be made deeply immutable without leaving
 * a writable alias in the receiver. Cross-isolate transports should serialize
 * Frozen values rather than sharing Java object references.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final int MAX_FREEZE_DEPTH = 256;
    private static final int MAX_FREEZE_NODES = 100_000;
    private static final long MAX_FREEZE_BYTES = 16L * 1024 * 1024;
    private static final int DEFAULT_MAX_ACTORS = 16_384;
    private static final Duration MAX_CLOSE_WAIT = Duration.ofSeconds(2);

    public record SchedulerConfig(
            int privateCarrierMin,
            int privateCarrierMax,
            int sharedPoolMin,
            int sharedPoolMax) {

        public SchedulerConfig {
            if (privateCarrierMin < 1 || privateCarrierMax < privateCarrierMin) {
                throw new IllegalArgumentException("invalid private actor carrier bounds");
            }
            if (sharedPoolMin < 1 || sharedPoolMax < sharedPoolMin) {
                throw new IllegalArgumentException("invalid shared actor pool bounds");
            }
        }

        public static SchedulerConfig defaults() {
            return new SchedulerConfig(
                    intProperty("ores.actor.private.carriers.min", 20),
                    intProperty("ores.actor.private.carriers.max", 40),
                    intProperty("ores.actor.shared.pool.min", 10),
                    intProperty("ores.actor.shared.pool.max", 20));
        }

        private static int intProperty(String name, int fallback) {
            String raw = System.getProperty(name);
            if (raw == null || raw.isBlank()) return fallback;
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(name + " must be an integer", invalid);
            }
        }
    }

    public record SchedulerSnapshot(
            int privateCarrierTarget,
            int privateCarrierPoolSize,
            long privateQueuedVirtualThreads,
            int sharedTargetParallelism,
            int sharedPoolSize,
            long sharedQueuedTasks) { }

    private static final ProcessActorSchedulers PROCESS_SCHEDULERS =
            new ProcessActorSchedulers(SchedulerConfig.defaults());

    public enum MemoryMode {
        SHARED_HEAP,
        PRIVATE_ARENA
    }

    public record MemoryPolicy(MemoryMode mode, long arenaBytes) {
        public MemoryPolicy {
            java.util.Objects.requireNonNull(mode, "mode");
            if (mode == MemoryMode.SHARED_HEAP && arenaBytes != 0) {
                throw new IllegalArgumentException("shared-heap actors must use arenaBytes=0");
            }
            if (mode == MemoryMode.PRIVATE_ARENA && arenaBytes <= 0) {
                throw new IllegalArgumentException("private-arena actors require arenaBytes > 0");
            }
        }

        public static MemoryPolicy sharedHeap() {
            return new MemoryPolicy(MemoryMode.SHARED_HEAP, 0);
        }

        public static MemoryPolicy privateArena(long bytes) {
            return new MemoryPolicy(MemoryMode.PRIVATE_ARENA, bytes);
        }

        public long initialArenaBytes() { return arenaBytes; }
    }

    public interface ActorMemory extends AutoCloseable {
        MemoryMode mode();
        long capacityBytes();
        long maxCapacityBytes();
        long usedBytes();
        int segmentCount();
        MemorySegment allocate(long byteSize, long byteAlignment);
        @Override void close();
    }

    private static final ThreadLocal<IsolatePolicy> CURRENT_ACTOR_POLICY = new ThreadLocal<>();
    private static final ThreadLocal<ActorRuntime> CURRENT_ACTOR_RUNTIME = new ThreadLocal<>();
    private static final ThreadLocal<Object> CURRENT_ACTOR_DOMAIN = new ThreadLocal<>();
    private static final ThreadLocal<MemoryMode> CURRENT_ACTOR_MEMORY_MODE = new ThreadLocal<>();

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger actorCount = new AtomicInteger();
    private final AtomicLong privateArenaReservedBytes = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final IsolatePolicy policyCeiling;
    private final int maxActors;

    public ActorRuntime() {
        this(IsolatePolicy.developer(), DEFAULT_MAX_ACTORS);
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this(policyCeiling, DEFAULT_MAX_ACTORS);
    }

    public ActorRuntime(IsolatePolicy policyCeiling, int maxActors) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
        if (maxActors <= 0 || maxActors > DEFAULT_MAX_ACTORS) {
            throw new IllegalArgumentException(
                    "maxActors must be between 1 and " + DEFAULT_MAX_ACTORS);
        }
        this.maxActors = maxActors;
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }
    public int maxActors() { return maxActors; }
    public boolean isClosed() { return closed.get(); }

    public static SchedulerConfig schedulerConfig() { return PROCESS_SCHEDULERS.config; }
    public static SchedulerSnapshot schedulerSnapshot() { return PROCESS_SCHEDULERS.snapshot(); }
    public long privateArenaReservedBytes() { return privateArenaReservedBytes.get(); }
    public long privateArenaBudgetBytes() { return policyCeiling.maxHeapBytes(); }

    private void requireCallerRuntimeAffinity(String operation) {
        ActorRuntime caller = CURRENT_ACTOR_RUNTIME.get();
        if (caller != null && caller != this) {
            throw new SecurityException(
                    "actor cannot " + operation + " through another ActorRuntime");
        }
    }

    private void requireSupervisorContext(String operation) {
        if (CURRENT_ACTOR_RUNTIME.get() != null) {
            throw new SecurityException(
                    "actor code cannot " + operation + "; this operation belongs to the host/supervisor");
        }
    }

    private boolean reserveActorSlot() {
        while (true) {
            int current = actorCount.get();
            if (current >= maxActors) return false;
            if (actorCount.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseActorSlot() {
        int remaining = actorCount.decrementAndGet();
        if (remaining < 0) {
            actorCount.incrementAndGet();
            throw new IllegalStateException("actor-count accounting underflow");
        }
    }

    /** True only while the current JVM thread is executing an actor behavior. */
    public static boolean inActorExecution() {
        return CURRENT_ACTOR_POLICY.get() != null;
    }

    /** Current actor policy for runtime primitives that need capability checks. */
    public static IsolatePolicy currentActorPolicy() {
        return CURRENT_ACTOR_POLICY.get();
    }

    /** Owning ActorRuntime for the actor currently executing on this thread. */
    static ActorRuntime currentActorRuntime() {
        return CURRENT_ACTOR_RUNTIME.get();
    }

    /**
     * Stable semantic execution domain for actor-local state.
     *
     * Shared actors may migrate between JVM worker threads, so actor-local
     * confinement must key off actor identity rather than Thread identity.
     * Outside actor execution, the current Thread is the local domain.
     */
    public static Object currentExecutionDomain() {
        Object actorDomain = CURRENT_ACTOR_DOMAIN.get();
        if (actorDomain != null) return actorDomain;
        if (CURRENT_ACTOR_POLICY.get() != null) {
            throw new IllegalStateException(
                    "actor execution is missing its semantic execution domain; refusing to fall back to JVM Thread identity");
        }
        return Thread.currentThread();
    }

    public record ActorId(UUID value) {
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    /**
     * Runtime-qualified semantic actor identity. Value equality lets a future
     * pooled scheduler recreate the token on each mailbox turn without tying
     * actor-local state to a particular JVM worker thread.
     */
    private record ActorDomain(ActorRuntime runtime, ActorId actorId) { }

    public static final class Shared<T> {
        private final T value;

        private Shared(T value) {
            this.value = value;
        }

        public T value() {
            return value;
        }

        @Override
        public String toString() {
            return "Shared[readonly]";
        }
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    @FunctionalInterface
    public interface Invocation<M, R> {
        R run(M message, ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
        ActorMemory memory();
    }

    public final class ActorRef<M> {
        private final ActorId id;

        private ActorRef(ActorId id) {
            this.id = id;
        }

        public ActorId id() { return id; }

        public void send(M message) {
            ActorRuntime callerRuntime = CURRENT_ACTOR_RUNTIME.get();
            if (callerRuntime != null && callerRuntime != ActorRuntime.this) {
                throw new SecurityException(
                        "ActorRef cannot be invoked from an actor owned by another ActorRuntime");
            }
            ActorRuntime.this.send(this, message);
        }

        private boolean belongsTo(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        @Override
        public String toString() {
            return "ActorRef[" + id.value() + "]";
        }
    }

    /**
     * Creates actor-local behavior inside the actor thread. The Supplier should
     * be generated by Oreslang lowering, not supplied from untrusted guest code.
     */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(policyCeiling, MemoryPolicy.sharedHeap(), behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            MemoryPolicy memoryPolicy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(
                policyForMemoryMode(policyCeiling, memoryPolicy.mode()),
                memoryPolicy,
                behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(policy, MemoryPolicy.sharedHeap(), behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            IsolatePolicy policy,
            MemoryPolicy memoryPolicy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnInternal(policy, memoryPolicy, behaviorFactory, failure -> { });
    }

    private <M> ActorRef<M> spawnInternal(
            IsolatePolicy policy,
            MemoryPolicy memoryPolicy,
            Supplier<? extends Behavior<M>> behaviorFactory,
            Consumer<Throwable> onTerminated) {
        requireCallerRuntimeAffinity("spawn actors");
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(memoryPolicy, "memoryPolicy");
        java.util.Objects.requireNonNull(behaviorFactory, "behaviorFactory");
        java.util.Objects.requireNonNull(onTerminated, "onTerminated");
        requireWithinCeiling(policy);
        requireMemoryPolicy(policy, memoryPolicy);

        synchronized (lifecycleLock) {
            if (closed.get()) throw new IllegalStateException("actor runtime is closed");
            if (!reserveActorSlot()) {
                throw new IllegalStateException("actor runtime limit exceeded: " + maxActors);
            }

            ActorId id = ActorId.create();
            ActorRef<M> ref = new ActorRef<>(id);
            ActorCell<M> cell = new ActorCell<>(
                    ref, policy, memoryPolicy, behaviorFactory, onTerminated);
            actors.put(id, cell);
            try {
                cell.start();
                return ref;
            } catch (Throwable startupFailure) {
                actors.remove(id, cell);
                releaseActorSlot();
                throw startupFailure;
            }
        }
    }

    private void requireMemoryPolicy(IsolatePolicy policy, MemoryPolicy memoryPolicy) {
        if (memoryPolicy.mode() != MemoryMode.PRIVATE_ARENA) return;
        if (memoryPolicy.arenaBytes() > policy.maxHeapBytes()) {
            throw new SecurityException("actor private arena exceeds actor maxHeapBytes policy");
        }
        if (policy.capabilities().contains(IsolatePolicy.Capability.SHARED_MEMORY)) {
            throw new SecurityException("PRIVATE_ARENA actors cannot have SHARED_MEMORY capability");
        }
        if (policy.capabilities().contains(IsolatePolicy.Capability.ACTOR_SHARE_READONLY)) {
            throw new SecurityException("PRIVATE_ARENA actors cannot have ACTOR_SHARE_READONLY capability");
        }
    }

    public <M, R> R invoke(
            M message,
            MemoryPolicy memoryPolicy,
            Invocation<M, R> invocation) {
        java.util.Objects.requireNonNull(memoryPolicy, "memoryPolicy");
        java.util.Objects.requireNonNull(invocation, "invocation");

        if (inActorExecution()
                && CURRENT_ACTOR_MEMORY_MODE.get() == MemoryMode.SHARED_HEAP) {
            throw new IllegalStateException(
                    "synchronous actor invocation from a shared actor would block the shared actor pool; use an async/suspendable actor call");
        }

        IsolatePolicy invocationPolicy = policyForMemoryMode(policyCeiling, memoryPolicy.mode());
        CompletableFuture<R> completion = new CompletableFuture<>();
        ActorRef<M> ref = spawnInternal(
                invocationPolicy,
                memoryPolicy,
                () -> (delivered, actorContext) -> {
                    try {
                        Object raw = invocation.run(delivered, actorContext);
                        @SuppressWarnings("unchecked")
                        R frozenResult = (R) freezeActorResult(raw, memoryPolicy.mode());
                        completion.complete(frozenResult);
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    } finally {
                        stop(actorContext.self());
                    }
                },
                failure -> {
                    if (completion.isDone()) return;
                    if (failure != null) completion.completeExceptionally(failure);
                    else completion.completeExceptionally(
                            new CancellationException(
                                    "actor invocation terminated before producing a result"));
                });

        try {
            send(ref, message);
            return completion.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } finally {
            stop(ref);
        }
    }

    private IsolatePolicy policyForMemoryMode(IsolatePolicy source, MemoryMode mode) {
        if (mode == MemoryMode.SHARED_HEAP) return source;
        java.util.EnumSet<IsolatePolicy.Capability> caps =
                java.util.EnumSet.noneOf(IsolatePolicy.Capability.class);
        caps.addAll(source.capabilities());
        caps.remove(IsolatePolicy.Capability.SHARED_MEMORY);
        caps.remove(IsolatePolicy.Capability.ACTOR_SHARE_READONLY);
        return new IsolatePolicy(
                caps,
                source.maxHeapBytes(),
                source.maxMailboxMessages(),
                source.maxWallTime(),
                source.adversarial());
    }

    public boolean stop(ActorRef<?> ref) {
        requireCallerRuntimeAffinity("stop actors");
        java.util.Objects.requireNonNull(ref, "ref");
        if (!ref.belongsTo(this)) {
            throw new IllegalArgumentException("ActorRef belongs to another ActorRuntime");
        }
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return false;
        cell.stop();
        return true;
    }

    private void requireWithinCeiling(IsolatePolicy child) {
        if (!policyCeiling.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(policyCeiling.capabilities());
            throw new SecurityException("child actor policy exceeds parent capabilities: " + excess);
        }
        if (child.maxHeapBytes() > policyCeiling.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds parent policy");
        }
        if (child.maxMailboxMessages() > policyCeiling.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds parent policy");
        }
        if (child.maxWallTime().compareTo(policyCeiling.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds parent policy");
        }
        if (policyCeiling.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial parent policy");
        }
    }

    @SuppressWarnings("unchecked")
    public <M> void send(ActorRef<M> ref, M message) {
        requireCallerRuntimeAffinity("send messages");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        if (ref == null || !ref.belongsTo(this)) {
            throw new IllegalArgumentException("ActorRef belongs to another ActorRuntime");
        }

        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) throw new IllegalStateException("unknown actor " + ref.id());
        if (!cell.reserveMailboxSlot()) {
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }

        boolean enqueued = false;
        try {
            Object frozen = freezeForThisRuntime(message);

            if (cell.memoryPolicy.mode() == MemoryMode.PRIVATE_ARENA
                    && containsReadonlyShared(frozen)) {
                throw new SecurityException(
                        "PRIVATE_ARENA actors cannot receive Shared<T> JVM-heap aliases");
            }

            Set<OresMutex.Shared<?>> sharedMutexes = sharedMutexesIn(frozen);
            if (!sharedMutexes.isEmpty()) {
                if (cell.memoryPolicy.mode() == MemoryMode.PRIVATE_ARENA) {
                    throw new SecurityException(
                            "PRIVATE_ARENA actors cannot receive SharedMutex<T>");
                }

                IsolatePolicy sender = CURRENT_ACTOR_POLICY.get();
                if (sender != null) {
                    sender.require(
                            IsolatePolicy.Capability.SHARED_MEMORY,
                            "SharedMutex actor send");
                } else {
                    policyCeiling.require(
                            IsolatePolicy.Capability.SHARED_MEMORY,
                            "SharedMutex host send");
                }
                cell.policy.require(
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        "SharedMutex actor receive");
            }

            synchronized (lifecycleLock) {
                if (closed.get()) {
                    throw new IllegalStateException("actor runtime is closed");
                }

                if (!sharedMutexes.isEmpty()) {
                    boolean compatible = OresMutex.publishToRuntime(
                            this,
                            sharedMutexes,
                            () -> {
                                if (!cell.enqueueReserved(frozen)) {
                                    throw new IllegalStateException(
                                            "actor terminated before message admission for "
                                                    + ref.id());
                                }
                            });
                    if (!compatible) {
                        throw new IllegalArgumentException(
                                "SharedMutex may cross actor mailboxes only within its owning ActorRuntime");
                    }
                } else if (!cell.enqueueReserved(frozen)) {
                    throw new IllegalStateException(
                            "actor terminated before message admission for " + ref.id());
                }

                enqueued = true;
            }

            // Publication/admission is complete before a shared actor can
            // observe the message.
            cell.messageAvailable();
        } finally {
            if (!enqueued) cell.releaseMailboxSlot();
        }
    }

    /**
     * Creates an explicitly read-only shared value. The returned graph is a
     * frozen representation; no mutable source object itself is exposed.
     */
    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     * It observes runtime shutdown/interruption and yields the carrier so
     * supervisor/control-plane work can run. This is intentionally a runtime
     * hook rather than guest-accessible thread control.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("actor execution interrupted");
        Thread.yield();
    }

    public Shared<Object> shareReadonly(Object value) {
        requireCallerRuntimeAffinity("share readonly values");
        IsolatePolicy callerPolicy = CURRENT_ACTOR_POLICY.get();
        if (callerPolicy != null) {
            callerPolicy.require(
                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                    "ActorRuntime.shareReadonly");
        } else {
            policyCeiling.require(
                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                    "ActorRuntime.shareReadonly");
        }
        return new Shared<>(freezeForThisRuntime(value));
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     */
    public static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>(), new FreezeBudget(), 0, null);
    }

    private Object freezeForThisRuntime(Object value) {
        return freeze(value, new IdentityHashMap<>(), new FreezeBudget(), 0, this);
    }

    private static Object freeze(
            Object value,
            IdentityHashMap<Object, Boolean> path,
            FreezeBudget budget,
            int depth,
            ActorRuntime allowedActorRuntime) {
        if (depth > MAX_FREEZE_DEPTH) {
            throw new IllegalArgumentException(
                    "actor message exceeds maximum nesting depth " + MAX_FREEZE_DEPTH);
        }
        budget.addNode();

        if (value instanceof MemorySegment) {
            throw new IllegalArgumentException(
                    "MemorySegment is actor-local memory and cannot cross actor boundaries");
        }
        if (value == null) {
            budget.addBytes(1);
            return null;
        }
        if (value instanceof String text) {
            budget.addBytes(16L + 2L * text.length());
            return text;
        }
        if (value instanceof BigInteger integer) {
            budget.addBytes(32L + Math.max(1L, (integer.bitLength() + 7L) / 8L));
            return integer;
        }
        if (value instanceof BigDecimal decimal) {
            budget.addBytes(40L + Math.max(1L,
                    (decimal.unscaledValue().bitLength() + 7L) / 8L));
            return decimal;
        }
        if (value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof Enum<?> || value instanceof UUID
                || value instanceof ActorId) {
            budget.addBytes(32);
            return value;
        }
        budget.addBytes(24);

        /*
         * SharedMutex is an explicitly capability-gated writable shared-memory
         * handle. Its protected payload is intentionally not copied here.
         */
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (allowedActorRuntime == null) {
                throw new IllegalArgumentException(
                        "SharedMutex is a runtime-scoped writable capability and cannot cross a generic freeze boundary");
            }
            return sharedMutex;
        }

        if (value instanceof ActorRef<?> ref) {
            if (allowedActorRuntime == null || !ref.belongsTo(allowedActorRuntime)) {
                throw new IllegalArgumentException(
                        "ActorRef capabilities may cross only within their owning ActorRuntime");
            }
            return ref;
        }

        /*
         * Revalidate readonly wrappers whenever they cross a boundary. This
         * prevents a wrapper created in one runtime from smuggling a foreign
         * ActorRef or any other runtime-scoped capability into another.
         */
        if (value instanceof Shared<?> shared) {
            enterComposite(value, path);
            try {
                return new Shared<>(freeze(
                        shared.value(), path, budget, depth + 1, allowedActorRuntime));
            } finally {
                path.remove(value);
            }
        }

        if (value instanceof List<?> list) {
            if (list.size() > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                List<Object> frozen = new ArrayList<>();
                for (Object item : list) {
                    frozen.add(freeze(item, path, budget, depth + 1, allowedActorRuntime));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Set<?> set) {
            if (set.size() > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                Set<Object> frozen = new LinkedHashSet<>();
                for (Object item : set) {
                    Object copy = freeze(item, path, budget, depth + 1, allowedActorRuntime);
                    if (!frozen.add(copy)) {
                        throw new IllegalArgumentException(
                                "actor message set elements collide after freezing");
                    }
                }
                return Collections.unmodifiableSet(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = freeze(
                            entry.getKey(), path, budget, depth + 1, allowedActorRuntime);
                    Object item = freeze(
                            entry.getValue(), path, budget, depth + 1, allowedActorRuntime);
                    if (frozen.containsKey(key)) {
                        throw new IllegalArgumentException(
                                "actor message map keys collide after freezing");
                    }
                    frozen.put(key, item);
                }
                return Collections.unmodifiableMap(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                List<Object> frozen = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    frozen.add(freeze(
                            Array.get(value, i), path, budget, depth + 1, allowedActorRuntime));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Sendable sendable) {
            enterComposite(value, path);
            try {
                Object candidate = sendable.freezeForSend();
                if (candidate == value) {
                    throw new IllegalArgumentException(
                            "Sendable.freezeForSend() must return a distinct frozen representation; self-returning mutable aliases are forbidden");
                }
                return freeze(candidate, path, budget, depth + 1, allowedActorRuntime);
            } finally {
                path.remove(value);
            }
        }
        throw new IllegalArgumentException("value of type " + value.getClass().getName()
                + " is not Sendable; mutable host objects cannot cross actor boundaries");
    }

    private static void enterComposite(
            Object value,
            IdentityHashMap<Object, Boolean> path) {
        if (path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "cyclic actor message graphs are not Sendable");
        }
    }

    private static final class FreezeBudget {
        private int nodes;
        private long bytes;

        private void addNode() {
            if (++nodes > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
        }

        private void addBytes(long amount) {
            if (amount < 0 || bytes > MAX_FREEZE_BYTES - amount) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum frozen size "
                                + MAX_FREEZE_BYTES + " bytes");
            }
            bytes += amount;
        }
    }

    private static Set<OresMutex.Shared<?>> sharedMutexesIn(Object value) {
        Set<OresMutex.Shared<?>> found = new LinkedHashSet<>();
        collectSharedMutexes(value, found);
        return java.util.Collections.unmodifiableSet(found);
    }

    private static void collectSharedMutexes(
            Object value,
            Set<OresMutex.Shared<?>> found) {
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            found.add(sharedMutex);
            return;
        }
        if (value instanceof Shared<?> shared) {
            collectSharedMutexes(shared.value(), found);
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) collectSharedMutexes(item, found);
            return;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) collectSharedMutexes(item, found);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectSharedMutexes(entry.getKey(), found);
                collectSharedMutexes(entry.getValue(), found);
            }
        }
    }

    private static boolean containsSharedMutex(Object value) {
        return !sharedMutexesIn(value).isEmpty();
    }

    private static boolean containsReadonlyShared(Object value) {
        if (value instanceof Shared<?>) return true;
        if (value instanceof List<?> list) {
            for (Object item : list) if (containsReadonlyShared(item)) return true;
            return false;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) if (containsReadonlyShared(item)) return true;
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsReadonlyShared(entry.getKey())
                        || containsReadonlyShared(entry.getValue())) return true;
            }
        }
        return false;
    }

    private Object freezeActorResult(Object value, MemoryMode sourceMode) {
        Object frozen = freezeForThisRuntime(value);
        if (sourceMode == MemoryMode.PRIVATE_ARENA
                && (containsReadonlyShared(frozen) || containsSharedMutex(frozen))) {
            throw new SecurityException(
                    "PRIVATE_ARENA actor results cannot expose shared JVM-heap capabilities");
        }
        return frozen;
    }

    /** Implemented by generated immutable Oreslang aggregate values. */
    public interface Sendable {
        Object freezeForSend();
    }

    private ActorMemory openActorMemory(
            MemoryPolicy memoryPolicy,
            IsolatePolicy actorPolicy) {
        return switch (memoryPolicy.mode()) {
            case SHARED_HEAP -> SharedHeapMemory.INSTANCE;
            case PRIVATE_ARENA -> new ConfinedArenaMemory(
                    memoryPolicy.arenaBytes(),
                    actorPolicy.maxHeapBytes());
        };
    }

    private enum SharedHeapMemory implements ActorMemory {
        INSTANCE;

        @Override public MemoryMode mode() { return MemoryMode.SHARED_HEAP; }
        @Override public long capacityBytes() { return 0; }
        @Override public long maxCapacityBytes() { return 0; }
        @Override public long usedBytes() { return 0; }
        @Override public int segmentCount() { return 0; }

        @Override
        public MemorySegment allocate(long byteSize, long byteAlignment) {
            throw new UnsupportedOperationException(
                    "shared-heap actors use managed values, not private MemorySegments");
        }

        @Override public void close() { }
    }

    private final class ConfinedArenaMemory implements ActorMemory {
        private final Arena arena = Arena.ofConfined();
        private final ArrayList<ArenaChunk> chunks = new ArrayList<>();
        private final long initialCapacityBytes;
        private final long maxCapacityBytes;
        private long capacityBytes;
        private long usedBytes;
        private boolean closed;

        private ConfinedArenaMemory(
                long initialCapacityBytes,
                long maxCapacityBytes) {
            if (initialCapacityBytes <= 0) {
                throw new IllegalArgumentException(
                        "initial private arena capacity must be positive");
            }
            if (maxCapacityBytes < initialCapacityBytes) {
                throw new IllegalArgumentException(
                        "private arena max capacity cannot be below initial capacity");
            }
            this.initialCapacityBytes = initialCapacityBytes;
            this.maxCapacityBytes = maxCapacityBytes;
            try {
                addChunk(initialCapacityBytes, 8);
            } catch (Throwable failure) {
                arena.close();
                throw failure;
            }
        }

        @Override public MemoryMode mode() { return MemoryMode.PRIVATE_ARENA; }
        @Override public long capacityBytes() { return capacityBytes; }
        @Override public long maxCapacityBytes() { return maxCapacityBytes; }
        @Override public long usedBytes() { return usedBytes; }
        @Override public int segmentCount() { return chunks.size(); }

        @Override
        public MemorySegment allocate(long byteSize, long byteAlignment) {
            ensureOpen();
            if (byteSize < 0) {
                throw new IllegalArgumentException("byteSize must be non-negative");
            }
            if (byteAlignment <= 0
                    || (byteAlignment & (byteAlignment - 1)) != 0) {
                throw new IllegalArgumentException(
                        "byteAlignment must be a positive power of two");
            }

            for (int i = chunks.size() - 1; i >= 0; i--) {
                MemorySegment allocated =
                        tryAllocate(chunks.get(i), byteSize, byteAlignment);
                if (allocated != null) return allocated;
            }

            ArenaChunk grown = grow(byteSize, byteAlignment);
            MemorySegment allocated =
                    tryAllocate(grown, byteSize, byteAlignment);
            if (allocated == null) {
                throw new AssertionError(
                        "fresh actor heap segment cannot satisfy allocation");
            }
            return allocated;
        }

        private MemorySegment tryAllocate(
                ArenaChunk chunk,
                long byteSize,
                long byteAlignment) {
            final long aligned;
            final long end;
            try {
                long absolute =
                        Math.addExact(chunk.segment.address(), chunk.cursor);
                long mask = byteAlignment - 1;
                long alignedAbsolute =
                        Math.addExact(absolute, mask) & ~mask;
                aligned =
                        Math.subtractExact(
                                alignedAbsolute,
                                chunk.segment.address());
                end = Math.addExact(aligned, byteSize);
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError(
                        "actor private arena allocation overflow");
            }
            if (end > chunk.segment.byteSize()) return null;

            try {
                long consumed = Math.subtractExact(end, chunk.cursor);
                usedBytes = Math.addExact(usedBytes, consumed);
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError(
                        "actor private arena usage accounting overflow");
            }

            MemorySegment slice =
                    chunk.segment.asSlice(aligned, byteSize);
            chunk.cursor = end;
            return slice;
        }

        private ArenaChunk grow(long byteSize, long byteAlignment) {
            final long remaining;
            try {
                remaining =
                        Math.subtractExact(
                                maxCapacityBytes,
                                capacityBytes);
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError(
                        "actor private arena capacity accounting overflow");
            }
            if (byteSize > remaining) {
                throw actorLimitExceeded(byteSize);
            }

            long previous = chunks.isEmpty()
                    ? initialCapacityBytes
                    : chunks.getLast().segment.byteSize();
            long doubled =
                    previous > Long.MAX_VALUE / 2
                            ? Long.MAX_VALUE
                            : previous * 2;
            long desired =
                    Math.max(
                            byteSize,
                            Math.max(initialCapacityBytes, doubled));
            long chunkBytes = Math.min(remaining, desired);
            if (chunkBytes < byteSize) {
                throw actorLimitExceeded(byteSize);
            }
            return addChunk(
                    chunkBytes,
                    Math.max(8, byteAlignment));
        }

        private ArenaChunk addChunk(
                long byteSize,
                long byteAlignment) {
            reservePrivateArenaBytes(byteSize);
            boolean committed = false;
            try {
                MemorySegment segment =
                        arena.allocate(byteSize, byteAlignment);
                ArenaChunk chunk = new ArenaChunk(segment);
                chunks.add(chunk);
                capacityBytes =
                        Math.addExact(capacityBytes, byteSize);
                committed = true;
                return chunk;
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError(
                        "actor private arena capacity accounting overflow");
            } finally {
                if (!committed) {
                    releasePrivateArenaBytes(byteSize);
                }
            }
        }

        private OutOfMemoryError actorLimitExceeded(
                long requestedBytes) {
            return new OutOfMemoryError(
                    "actor private arena hard limit exceeded: requested="
                            + requestedBytes
                            + " used=" + usedBytes
                            + " committed=" + capacityBytes
                            + " max=" + maxCapacityBytes);
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "actor private arena is closed");
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            long committed = capacityBytes;
            try {
                arena.close();
            } finally {
                chunks.clear();
                capacityBytes = 0;
                usedBytes = 0;
                releasePrivateArenaBytes(committed);
            }
        }
    }

    private static final class ArenaChunk {
        private final MemorySegment segment;
        private long cursor;

        private ArenaChunk(MemorySegment segment) {
            this.segment = segment;
        }
    }

    private void reservePrivateArenaBytes(long bytes) {
        while (true) {
            long current = privateArenaReservedBytes.get();
            final long next;
            try {
                next = Math.addExact(current, bytes);
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError(
                        "tenant private arena reservation overflow");
            }
            if (next > privateArenaBudgetBytes()) {
                throw new OutOfMemoryError(
                        "tenant private arena budget exhausted: requested="
                                + bytes
                                + " reserved=" + current
                                + " budget=" + privateArenaBudgetBytes());
            }
            if (privateArenaReservedBytes.compareAndSet(
                    current, next)) {
                return;
            }
        }
    }

    private void releasePrivateArenaBytes(long bytes) {
        if (bytes == 0) return;
        long remaining =
                privateArenaReservedBytes.addAndGet(-bytes);
        if (remaining < 0) {
            privateArenaReservedBytes.addAndGet(bytes);
            throw new IllegalStateException(
                    "actor private arena reservation accounting underflow");
        }
    }

    private static final class ProcessActorSchedulers {
        private static final int SCALE_STEP = 2;

        private final SchedulerConfig config;
        private final ForkJoinPool sharedPool;
        private final VirtualThreadSchedulerMXBean virtualScheduler;
        private final ScheduledThreadPoolExecutor controller;
        private final AtomicInteger sharedWorkerSequence =
                new AtomicInteger();
        private int privateIdleTicks;
        private int sharedIdleTicks;

        private ProcessActorSchedulers(SchedulerConfig config) {
            this.config = config;

            setDefaultProperty(
                    "jdk.virtualThreadScheduler.parallelism",
                    Integer.toString(config.privateCarrierMin()));
            setDefaultProperty(
                    "jdk.virtualThreadScheduler.maxPoolSize",
                    Integer.toString(config.privateCarrierMax()));

            this.sharedPool = new ForkJoinPool(
                    config.sharedPoolMin(),
                    pool -> {
                        ForkJoinWorkerThread worker =
                                ForkJoinPool
                                        .defaultForkJoinWorkerThreadFactory
                                        .newThread(pool);
                        worker.setName(
                                "ores-shared-actor-"
                                        + sharedWorkerSequence
                                                .incrementAndGet());
                        return worker;
                    },
                    null,
                    true,
                    0,
                    config.sharedPoolMax(),
                    1,
                    null,
                    30L,
                    TimeUnit.SECONDS);

            VirtualThreadSchedulerMXBean scheduler = null;
            try {
                scheduler =
                        ManagementFactory.getPlatformMXBean(
                                VirtualThreadSchedulerMXBean.class);
                if (scheduler != null) {
                    scheduler.setParallelism(
                            clamp(
                                    scheduler.getParallelism(),
                                    config.privateCarrierMin(),
                                    config.privateCarrierMax()));
                }
            } catch (UnsupportedOperationException
                    | SecurityException
                    | LinkageError unavailable) {
                // Alternate JVM/native-image environments may omit it.
            }
            this.virtualScheduler = scheduler;

            this.controller =
                    new ScheduledThreadPoolExecutor(
                            1,
                            runnable ->
                                    Thread.ofPlatform()
                                            .daemon(true)
                                            .name(
                                                    "ores-actor-scheduler-controller")
                                            .unstarted(runnable));
            this.controller.setRemoveOnCancelPolicy(true);
            this.controller.scheduleWithFixedDelay(
                    this::rebalanceSafely,
                    250L,
                    250L,
                    TimeUnit.MILLISECONDS);
        }

        private void executeShared(Runnable task) {
            sharedPool.execute(task);
        }

        private SchedulerSnapshot snapshot() {
            int privateTarget =
                    virtualScheduler == null
                            ? -1
                            : virtualScheduler.getParallelism();
            int privatePoolSize =
                    virtualScheduler == null
                            ? -1
                            : virtualScheduler.getPoolSize();
            long privateQueued =
                    virtualScheduler == null
                            ? -1L
                            : virtualScheduler
                                    .getQueuedVirtualThreadCount();
            return new SchedulerSnapshot(
                    privateTarget,
                    privatePoolSize,
                    privateQueued,
                    sharedPool.getParallelism(),
                    sharedPool.getPoolSize(),
                    sharedPool.getQueuedSubmissionCount()
                            + sharedPool.getQueuedTaskCount());
        }

        private void rebalanceSafely() {
            try {
                rebalancePrivate();
                rebalanceShared();
            } catch (RuntimeException | LinkageError ignored) {
                // Telemetry/tuning failures must not take down actor work.
            }
        }

        private void rebalancePrivate() {
            if (virtualScheduler == null) return;
            int current = virtualScheduler.getParallelism();
            long queued =
                    Math.max(
                            0L,
                            virtualScheduler
                                    .getQueuedVirtualThreadCount());
            int mounted =
                    Math.max(
                            0,
                            virtualScheduler
                                    .getMountedVirtualThreadCount());

            int desired = current;
            if (queued > 0
                    && current < config.privateCarrierMax()) {
                privateIdleTicks = 0;
                desired =
                        Math.min(
                                config.privateCarrierMax(),
                                current + SCALE_STEP);
            } else if (queued == 0
                    && mounted < current / 2) {
                privateIdleTicks++;
                if (privateIdleTicks >= 8
                        && current > config.privateCarrierMin()) {
                    desired =
                            Math.max(
                                    config.privateCarrierMin(),
                                    current - SCALE_STEP);
                    privateIdleTicks = 0;
                }
            } else {
                privateIdleTicks = 0;
            }

            if (desired != current) {
                virtualScheduler.setParallelism(desired);
            }
        }

        private void rebalanceShared() {
            int current = sharedPool.getParallelism();
            long queued =
                    sharedPool.getQueuedSubmissionCount()
                            + sharedPool.getQueuedTaskCount();
            int active = sharedPool.getActiveThreadCount();

            int desired = current;
            if (queued > current
                    && current < config.sharedPoolMax()) {
                sharedIdleTicks = 0;
                desired =
                        Math.min(
                                config.sharedPoolMax(),
                                current + SCALE_STEP);
            } else if (queued == 0
                    && active < current / 2) {
                sharedIdleTicks++;
                if (sharedIdleTicks >= 8
                        && current > config.sharedPoolMin()) {
                    desired =
                            Math.max(
                                    config.sharedPoolMin(),
                                    current - SCALE_STEP);
                    sharedIdleTicks = 0;
                }
            } else {
                sharedIdleTicks = 0;
            }

            if (desired != current) {
                sharedPool.setParallelism(desired);
            }
        }

        private static void setDefaultProperty(
                String name,
                String value) {
            if (System.getProperty(name) == null) {
                System.setProperty(name, value);
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    @Override
    public void close() {
        requireSupervisorContext("close an ActorRuntime");

        final List<ActorCell<?>> snapshot;
        synchronized (lifecycleLock) {
            closed.set(true);
            snapshot = List.copyOf(actors.values());
        }

        for (ActorCell<?> cell : snapshot) cell.stop();

        long deadline = System.nanoTime() + MAX_CLOSE_WAIT.toNanos();
        boolean interrupted = false;
        List<ActorId> stillRunning = new ArrayList<>();
        for (ActorCell<?> cell : snapshot) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    cell.awaitStopped(remaining);
                } catch (InterruptedException stopWaitInterrupted) {
                    interrupted = true;
                    break;
                }
            }
            if (cell.isAlive()) stillRunning.add(cell.ref.id());
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
            for (ActorCell<?> cell : snapshot) {
                if (cell.isAlive() && !stillRunning.contains(cell.ref.id())) {
                    stillRunning.add(cell.ref.id());
                }
            }
        }
        if (!stillRunning.isEmpty()) {
            throw new IllegalStateException(
                    "ActorRuntime close did not observe full actor termination: "
                            + stillRunning.size() + " actor(s) still running");
        }
    }

    private final class ActorCell<M> {
        private static final int SHARED_TURN_MESSAGE_BUDGET = 64;

        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final MemoryPolicy memoryPolicy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final Consumer<Throwable> onTerminated;
        private final BlockingQueue<Object> mailbox;
        private final AtomicInteger queuedMessages = new AtomicInteger();
        private final AtomicBoolean sharedTurnScheduled =
                new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean terminationNotified =
                new AtomicBoolean();
        private final CountDownLatch terminatedLatch =
                new CountDownLatch(1);

        private volatile boolean terminated;
        private volatile Thread privateActorThread;
        private volatile Thread sharedExecutingThread;
        private volatile Behavior<M> sharedBehavior;
        private volatile ActorContext<M> sharedContext;

        private ActorCell(
                ActorRef<M> ref,
                IsolatePolicy policy,
                MemoryPolicy memoryPolicy,
                Supplier<? extends Behavior<M>> behaviorFactory,
                Consumer<Throwable> onTerminated) {
            this.ref = ref;
            this.policy = policy;
            this.memoryPolicy = memoryPolicy;
            this.behaviorFactory = behaviorFactory;
            this.onTerminated = onTerminated;
            this.mailbox =
                    new LinkedBlockingQueue<>(
                            policy.maxMailboxMessages());
        }

        private boolean reserveMailboxSlot() {
            if (stopped.get() || terminated) return false;
            while (true) {
                int current = queuedMessages.get();
                if (current >= policy.maxMailboxMessages()) {
                    return false;
                }
                if (queuedMessages.compareAndSet(
                        current, current + 1)) {
                    return true;
                }
                if (stopped.get() || terminated) return false;
            }
        }

        private void releaseMailboxSlot() {
            int remaining = queuedMessages.decrementAndGet();
            if (remaining < 0) {
                queuedMessages.incrementAndGet();
                throw new IllegalStateException(
                        "actor mailbox accounting underflow for "
                                + ref.id());
            }
        }

        private synchronized boolean enqueueReserved(
                Object message) {
            if (terminated || stopped.get() || closed.get()) {
                return false;
            }
            if (!mailbox.offer(message)) {
                throw new IllegalStateException(
                        "actor mailbox physical capacity unexpectedly exhausted for "
                                + ref.id());
            }
            return true;
        }

        private void start() {
            if (memoryPolicy.mode()
                    == MemoryMode.PRIVATE_ARENA) {
                privateActorThread =
                        Thread.ofVirtual()
                                .name(
                                        "ores-private-actor-"
                                                + ref.id().value())
                                .start(this::runPrivateActor);
            } else {
                // Eagerly initialize shared behavior so factory/startup
                // failures remove the actor even before its first message.
                scheduleSharedTurn();
            }
        }

        private void messageAvailable() {
            if (memoryPolicy.mode()
                    == MemoryMode.SHARED_HEAP) {
                scheduleSharedTurn();
            }
        }

        @SuppressWarnings("unchecked")
        private void runPrivateActor() {
            Throwable terminalFailure = null;
            installActorThreadLocals();
            try (ActorMemory memory =
                    openActorMemory(memoryPolicy, policy)) {
                Behavior<M> behavior =
                        java.util.Objects.requireNonNull(
                                behaviorFactory.get(),
                                "actor behavior factory returned null");
                ActorContext<M> context =
                        newContext(memory);

                while (!stopped.get() && !closed.get()) {
                    Object message = mailbox.take();
                    releaseMailboxSlot();
                    if (stopped.get() || closed.get()) break;
                    behavior.onMessage((M) message, context);
                }
            } catch (InterruptedException interrupted) {
                if (!stopped.get() && !closed.get()) {
                    terminalFailure = interrupted;
                }
                Thread.currentThread().interrupt();
            } catch (VirtualMachineError fatal) {
                terminalFailure = fatal;
                throw fatal;
            } catch (ThreadDeath death) {
                terminalFailure = death;
                throw death;
            } catch (Throwable failure) {
                terminalFailure = failure;
            } finally {
                clearActorThreadLocals();
                terminate(terminalFailure);
            }
        }

        private void scheduleSharedTurn() {
            if (terminated) return;
            if (sharedTurnScheduled.compareAndSet(
                    false, true)) {
                try {
                    PROCESS_SCHEDULERS.executeShared(
                            this::runSharedTurn);
                } catch (VirtualMachineError fatal) {
                    sharedTurnScheduled.set(false);
                    terminate(fatal);
                    throw fatal;
                } catch (ThreadDeath death) {
                    sharedTurnScheduled.set(false);
                    terminate(death);
                    throw death;
                } catch (RuntimeException schedulingFailure) {
                    sharedTurnScheduled.set(false);
                    terminate(schedulingFailure);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void runSharedTurn() {
            Throwable terminalFailure = null;
            boolean reschedule = false;
            Thread worker = Thread.currentThread();
            installActorThreadLocals();
            try {
                synchronized (this) {
                    if (terminated || stopped.get() || closed.get()) return;
                    sharedExecutingThread = worker;
                }

                Behavior<M> behavior = sharedBehavior;
                ActorContext<M> context = sharedContext;
                if (behavior == null) {
                    behavior =
                            java.util.Objects.requireNonNull(
                                    behaviorFactory.get(),
                                    "actor behavior factory returned null");
                    context =
                            newContext(SharedHeapMemory.INSTANCE);
                    sharedBehavior = behavior;
                    sharedContext = context;
                }

                int processed = 0;
                while (processed
                        < SHARED_TURN_MESSAGE_BUDGET) {
                    if (stopped.get() || closed.get()) break;
                    Object message = mailbox.poll();
                    if (message == null) break;
                    releaseMailboxSlot();
                    behavior.onMessage((M) message, context);
                    processed++;
                }
                reschedule =
                        !stopped.get()
                                && !closed.get()
                                && !mailbox.isEmpty();
            } catch (VirtualMachineError fatal) {
                terminalFailure = fatal;
                throw fatal;
            } catch (ThreadDeath death) {
                terminalFailure = death;
                throw death;
            } catch (Throwable failure) {
                terminalFailure = failure;
            } finally {
                synchronized (this) {
                    if (sharedExecutingThread == worker) {
                        sharedExecutingThread = null;
                    }
                }
                clearActorThreadLocals();

                // Never leak actor cancellation interrupt state back into the
                // shared ForkJoinPool worker's next unrelated task.
                if (worker.isInterrupted()) Thread.interrupted();

                sharedTurnScheduled.set(false);

                if (terminalFailure != null
                        || stopped.get()
                        || closed.get()) {
                    terminate(terminalFailure);
                } else if (reschedule || !mailbox.isEmpty()) {
                    scheduleSharedTurn();
                }
            }
        }

        private void installActorThreadLocals() {
            CURRENT_ACTOR_POLICY.set(policy);
            CURRENT_ACTOR_RUNTIME.set(ActorRuntime.this);
            CURRENT_ACTOR_DOMAIN.set(
                    new ActorDomain(
                            ActorRuntime.this, ref.id()));
            CURRENT_ACTOR_MEMORY_MODE.set(
                    memoryPolicy.mode());
        }

        private void clearActorThreadLocals() {
            CURRENT_ACTOR_MEMORY_MODE.remove();
            CURRENT_ACTOR_DOMAIN.remove();
            CURRENT_ACTOR_RUNTIME.remove();
            CURRENT_ACTOR_POLICY.remove();
        }

        private ActorContext<M> newContext(
                ActorMemory memory) {
            return new ActorContext<>() {
                @Override
                public ActorRef<M> self() { return ref; }

                @Override
                public ActorRuntime runtime() {
                    return ActorRuntime.this;
                }

                @Override
                public IsolatePolicy policy() {
                    return policy;
                }

                @Override
                public ActorMemory memory() {
                    return memory;
                }
            };
        }

        private void terminate(Throwable failure) {
            synchronized (this) {
                if (terminated) return;
                terminated = true;
                stopped.set(true);

                /*
                 * queuedMessages also includes senders that reserved a slot but
                 * are still freezing their message. Remove only entries that
                 * are physically queued here; in-flight senders will observe
                 * terminated in enqueueReserved() and release their own slot.
                 */
                int dropped = mailbox.size();
                mailbox.clear();
                if (dropped != 0) {
                    int remaining = queuedMessages.addAndGet(-dropped);
                    if (remaining < 0) {
                        queuedMessages.addAndGet(dropped);
                        throw new IllegalStateException(
                                "actor mailbox accounting underflow while terminating "
                                        + ref.id());
                    }
                }
            }

            if (actors.remove(ref.id(), this)) {
                releaseActorSlot();
            }
            notifyTerminated(failure);
            terminatedLatch.countDown();
        }

        private void notifyTerminated(Throwable failure) {
            if (terminationNotified.compareAndSet(
                    false, true)) {
                try {
                    onTerminated.accept(failure);
                } catch (RuntimeException ignored) {
                    // Lifecycle notification must not destabilize runtime cleanup.
                }
            }
        }

        private boolean isAlive() {
            return !terminated;
        }

        private void awaitStopped(long remainingNanos)
                throws InterruptedException {
            if (remainingNanos <= 0 || terminated) return;
            terminatedLatch.await(
                    remainingNanos,
                    TimeUnit.NANOSECONDS);
        }

        private void stop() {
            if (terminated) return;
            stopped.set(true);

            if (memoryPolicy.mode()
                    == MemoryMode.PRIVATE_ARENA) {
                Thread actorThread = privateActorThread;
                if (actorThread != null
                        && actorThread
                                != Thread.currentThread()) {
                    actorThread.interrupt();
                }
            } else {
                Thread worker;
                synchronized (this) {
                    worker = sharedExecutingThread;
                }
                if (worker != null && worker != Thread.currentThread()) {
                    worker.interrupt();
                }
                scheduleSharedTurn();
            }
        }
    }

}
