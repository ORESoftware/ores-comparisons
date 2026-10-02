package dev.oreslang.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
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
    /**
     * JVM-level actor scheduling policy.
     *
     * PRIVATE_ARENA actors are one virtual Thread per actor. Their virtual
     * threads are multiplexed by the JDK carrier scheduler, whose target
     * parallelism is kept between privateCarrierMin and privateCarrierMax.
     *
     * SHARED_HEAP actors do not own Threads. Their serial mailbox turns are
     * multiplexed over a distinct ForkJoinPool whose target parallelism is
     * kept between sharedPoolMin and sharedPoolMax.
     */
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

    /**
     * Actor-local allocation policy inside one tenant Graal isolate.
     *
     * SHARED_HEAP keeps ordinary actor objects on the tenant isolate's managed Java heap.
     * PRIVATE_ARENA gives the actor virtual-Thread-confined native memory segments and
     * a growing bump allocator. arenaBytes is the initial committed capacity;
     * more segments are committed on demand up to the actor and tenant limits.
     * Actor language/runtime objects only live there when their representation
     * explicitly allocates through ActorMemory.
     */
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

        /**
         * Creates a growable private arena policy. `bytes` is the initial
         * committed segment size; the actor may grow beyond it up to its
         * IsolatePolicy.maxHeapBytes() and the tenant runtime budget.
         */
        public static MemoryPolicy privateArena(long bytes) {
            return new MemoryPolicy(MemoryMode.PRIVATE_ARENA, bytes);
        }

        public long initialArenaBytes() {
            return arenaBytes;
        }
    }

    /**
     * Actor-scoped memory service. PRIVATE_ARENA implementations are confined
     * to the actor's dedicated virtual Thread and are closed when that actor exits.
     */
    public interface ActorMemory extends AutoCloseable {
        MemoryMode mode();
        long capacityBytes();
        long maxCapacityBytes();
        long usedBytes();
        int segmentCount();

        /**
         * Allocates a slice from the private arena. PRIVATE_ARENA allocations
         * grow the actor heap automatically when the current committed segments
         * cannot satisfy the request, subject to the actor and tenant limits.
         * Shared-heap actors should
         * use ordinary managed Oreslang/JVM allocation instead.
         */
        MemorySegment allocate(long byteSize, long byteAlignment);

        @Override
        void close();
    }

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong privateArenaReservedBytes = new AtomicLong();
    private final IsolatePolicy policyCeiling;

    public ActorRuntime() {
        this(IsolatePolicy.developer());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }

    public static SchedulerConfig schedulerConfig() {
        return PROCESS_SCHEDULERS.config;
    }

    public static SchedulerSnapshot schedulerSnapshot() {
        return PROCESS_SCHEDULERS.snapshot();
    }

    /** Native memory currently committed by PRIVATE_ARENA actors in this tenant runtime. */
    public long privateArenaReservedBytes() { return privateArenaReservedBytes.get(); }

    /**
     * PRIVATE_ARENA actors share this tenant-level native-memory budget.
     * An actor may have a stricter per-actor maxHeapBytes policy.
     */
    public long privateArenaBudgetBytes() { return policyCeiling.maxHeapBytes(); }

    public record ActorId(UUID value) {
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    public record Shared<T>(T value) {
        @SuppressWarnings("unchecked")
        public Shared {
            value = (T) freeze(value);
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
            ActorRuntime.this.send(this, message);
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
        return spawn(policyCeiling, memoryPolicy, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
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
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        java.util.Objects.requireNonNull(memoryPolicy, "memoryPolicy");
        java.util.Objects.requireNonNull(behaviorFactory, "behaviorFactory");
        java.util.Objects.requireNonNull(onTerminated, "onTerminated");
        requireWithinCeiling(policy);
        if (memoryPolicy.mode() == MemoryMode.PRIVATE_ARENA
                && memoryPolicy.arenaBytes() > policy.maxHeapBytes()) {
            throw new SecurityException("actor private arena exceeds actor maxHeapBytes policy");
        }
        ActorId id = ActorId.create();
        ActorRef<M> ref = new ActorRef<>(id);
        ActorCell<M> cell = new ActorCell<>(ref, policy, memoryPolicy, behaviorFactory, onTerminated);
        actors.put(id, cell);
        cell.start();
        return ref;
    }

    /**
     * Runs exactly one message on a freshly-created actor and returns its result.
     *
     * This is the lowering target for the source-level actor/isoactor callable
     * modifiers. The message and result both cross the same Sendable freeze
     * boundary as ordinary actor traffic. The actor is fail-stop and is
     * terminated after the invocation completes.
     */
    public <M, R> R invoke(
            M message,
            MemoryPolicy memoryPolicy,
            Invocation<M, R> invocation) {
        java.util.Objects.requireNonNull(memoryPolicy, "memoryPolicy");
        java.util.Objects.requireNonNull(invocation, "invocation");

        CompletableFuture<R> completion = new CompletableFuture<>();
        ActorRef<M> ref = spawnInternal(
                policyCeiling,
                memoryPolicy,
                () -> (delivered, actorContext) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        R frozenResult = (R) freeze(invocation.run(delivered, actorContext));
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
                            new CancellationException("actor invocation terminated before producing a result"));
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

    /**
     * Requests fail-stop termination of an actor. Returns false when the actor
     * has already terminated or is unknown to this runtime.
     */
    public boolean stop(ActorRef<?> ref) {
        java.util.Objects.requireNonNull(ref, "ref");
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
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) throw new IllegalStateException("unknown actor " + ref.id());
        Object frozen = freeze(message);
        if (!cell.mailbox.offer(frozen)) {
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }
        cell.messageAvailable();
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

    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        return new Shared<>((T) freeze(value));
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     */
    public static Object freeze(Object value) {
        if (value instanceof MemorySegment) {
            throw new IllegalArgumentException(
                    "MemorySegment is actor-local memory and cannot cross actor boundaries");
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId) {
            return value;
        }
        if (value instanceof Shared<?> shared) return shared;
        if (value instanceof ActorRef<?> ref) return ref;
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) frozen.add(freeze(item));
            return List.copyOf(frozen);
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(ActorRuntime::freeze).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) frozen.put(freeze(entry.getKey()), freeze(entry.getValue()));
            return Map.copyOf(frozen);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> frozen = new ArrayList<>(length);
            for (int i = 0; i < length; i++) frozen.add(freeze(Array.get(value, i)));
            return List.copyOf(frozen);
        }
        if (value instanceof Sendable sendable) return sendable.freezeForSend();
        throw new IllegalArgumentException("value of type " + value.getClass().getName()
                + " is not Sendable; mutable host objects cannot cross actor boundaries");
    }

    /** Implemented by generated immutable Oreslang aggregate values. */
    public interface Sendable {
        Object freezeForSend();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (ActorCell<?> cell : actors.values()) cell.stop();
        actors.clear();
    }

    private ActorMemory openActorMemory(MemoryPolicy memoryPolicy, IsolatePolicy actorPolicy) {
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
                    "shared-heap actors allocate ordinary managed objects, not private MemorySegments");
        }

        @Override public void close() { }
    }

    /**
     * Segmented native heap owned by exactly one actor virtual Thread.
     *
     * The Arena itself stays confined to that actor virtual Thread identity.
     * Carrier-thread migration does not change that owner identity. We commit an initial
     * segment, then geometrically grow with additional segments when needed.
     * Existing MemorySegments never move, so pointers/slices already handed to
     * the actor remain stable across growth.
     */
    private final class ConfinedArenaMemory implements ActorMemory {
        private final Arena arena = Arena.ofConfined();
        private final ArrayList<ArenaChunk> chunks = new ArrayList<>();
        private final long initialCapacityBytes;
        private final long maxCapacityBytes;
        private long capacityBytes;
        private long usedBytes;
        private boolean closed;

        private ConfinedArenaMemory(long initialCapacityBytes, long maxCapacityBytes) {
            if (initialCapacityBytes <= 0) {
                throw new IllegalArgumentException("initial private arena capacity must be positive");
            }
            if (maxCapacityBytes < initialCapacityBytes) {
                throw new IllegalArgumentException("private arena max capacity cannot be below initial capacity");
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
            if (byteSize < 0) throw new IllegalArgumentException("byteSize must be non-negative");
            if (byteAlignment <= 0 || (byteAlignment & (byteAlignment - 1)) != 0) {
                throw new IllegalArgumentException("byteAlignment must be a positive power of two");
            }

            // Prefer the newest chunk, but reuse an older tail if a smaller
            // allocation still fits there. This keeps segmented growth from
            // turning small tail fragments into permanent waste.
            for (int i = chunks.size() - 1; i >= 0; i--) {
                MemorySegment allocated = tryAllocate(chunks.get(i), byteSize, byteAlignment);
                if (allocated != null) return allocated;
            }

            ArenaChunk grown = grow(byteSize, byteAlignment);
            MemorySegment allocated = tryAllocate(grown, byteSize, byteAlignment);
            if (allocated == null) {
                throw new AssertionError("fresh actor heap segment cannot satisfy allocation");
            }
            return allocated;
        }

        private MemorySegment tryAllocate(ArenaChunk chunk, long byteSize, long byteAlignment) {
            final long aligned;
            final long end;
            try {
                long absolute = Math.addExact(chunk.segment.address(), chunk.cursor);
                long mask = byteAlignment - 1;
                long alignedAbsolute = Math.addExact(absolute, mask) & ~mask;
                aligned = Math.subtractExact(alignedAbsolute, chunk.segment.address());
                end = Math.addExact(aligned, byteSize);
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError("actor private arena allocation overflow");
            }
            if (end > chunk.segment.byteSize()) return null;

            long consumed;
            try {
                consumed = Math.subtractExact(end, chunk.cursor);
                usedBytes = Math.addExact(usedBytes, consumed);
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError("actor private arena usage accounting overflow");
            }
            MemorySegment slice = chunk.segment.asSlice(aligned, byteSize);
            chunk.cursor = end;
            return slice;
        }

        private ArenaChunk grow(long byteSize, long byteAlignment) {
            long remaining;
            try {
                remaining = Math.subtractExact(maxCapacityBytes, capacityBytes);
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError("actor private arena capacity accounting overflow");
            }
            if (byteSize > remaining) {
                throw actorLimitExceeded(byteSize);
            }

            long previous = chunks.isEmpty()
                    ? initialCapacityBytes
                    : chunks.getLast().segment.byteSize();
            long doubled = previous > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : previous * 2;
            long desired = Math.max(byteSize, Math.max(initialCapacityBytes, doubled));
            long chunkBytes = Math.min(remaining, desired);
            if (chunkBytes < byteSize) throw actorLimitExceeded(byteSize);

            return addChunk(chunkBytes, Math.max(8, byteAlignment));
        }

        private ArenaChunk addChunk(long byteSize, long byteAlignment) {
            reservePrivateArenaBytes(byteSize);
            boolean committed = false;
            try {
                MemorySegment segment = arena.allocate(byteSize, byteAlignment);
                ArenaChunk chunk = new ArenaChunk(segment);
                chunks.add(chunk);
                capacityBytes = Math.addExact(capacityBytes, byteSize);
                committed = true;
                return chunk;
            } catch (ArithmeticException overflow) {
                throw new OutOfMemoryError("actor private arena capacity accounting overflow");
            } finally {
                if (!committed) releasePrivateArenaBytes(byteSize);
            }
        }

        private OutOfMemoryError actorLimitExceeded(long requestedBytes) {
            return new OutOfMemoryError(
                    "actor private arena hard limit exceeded: requested=" + requestedBytes
                            + " used=" + usedBytes
                            + " committed=" + capacityBytes
                            + " max=" + maxCapacityBytes);
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("actor private arena is closed");
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
                throw new OutOfMemoryError("tenant private arena reservation overflow");
            }
            if (next > privateArenaBudgetBytes()) {
                throw new OutOfMemoryError(
                        "tenant private arena budget exhausted: requested=" + bytes
                                + " reserved=" + current
                                + " budget=" + privateArenaBudgetBytes());
            }
            if (privateArenaReservedBytes.compareAndSet(current, next)) return;
        }
    }

    private void releasePrivateArenaBytes(long bytes) {
        if (bytes == 0) return;
        long remaining = privateArenaReservedBytes.addAndGet(-bytes);
        if (remaining < 0) {
            privateArenaReservedBytes.addAndGet(bytes);
            throw new IllegalStateException("actor private arena reservation accounting underflow");
        }
    }

    /**
     * Process-wide schedulers. The JDK exposes one virtual-thread carrier
     * scheduler per JVM, so PRIVATE_ARENA actors use that scheduler and Oreslang
     * dynamically tunes its target parallelism. SHARED_HEAP actors use a
     * completely separate pool.
     */
    private static final class ProcessActorSchedulers {
        private static final int SCALE_STEP = 2;
        private final SchedulerConfig config;
        private final ForkJoinPool sharedPool;
        private final VirtualThreadSchedulerMXBean virtualScheduler;
        private final ScheduledThreadPoolExecutor controller;
        private final AtomicInteger sharedWorkerSequence = new AtomicInteger();
        private int privateIdleTicks;
        private int sharedIdleTicks;

        private ProcessActorSchedulers(SchedulerConfig config) {
            this.config = config;

            // These properties are consumed when the JDK virtual-thread
            // scheduler is initialized. Respect an embedding host that already
            // supplied explicit values.
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
                                ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                        worker.setName("ores-shared-actor-" + sharedWorkerSequence.incrementAndGet());
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
                scheduler = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
                if (scheduler != null) {
                    scheduler.setParallelism(clamp(
                            scheduler.getParallelism(),
                            config.privateCarrierMin(),
                            config.privateCarrierMax()));
                }
            } catch (UnsupportedOperationException | SecurityException | LinkageError unavailable) {
                // Native-image or an alternate JVM may not expose the management
                // bean. Startup properties still provide the configured bounds.
            }
            this.virtualScheduler = scheduler;

            this.controller = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = Thread.ofPlatform()
                        .daemon(true)
                        .name("ores-actor-scheduler-controller")
                        .unstarted(runnable);
                return thread;
            });
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
            int privateTarget = virtualScheduler == null ? -1 : virtualScheduler.getParallelism();
            int privatePoolSize = virtualScheduler == null ? -1 : virtualScheduler.getPoolSize();
            long privateQueued = virtualScheduler == null ? -1L : virtualScheduler.getQueuedVirtualThreadCount();
            return new SchedulerSnapshot(
                    privateTarget,
                    privatePoolSize,
                    privateQueued,
                    sharedPool.getParallelism(),
                    sharedPool.getPoolSize(),
                    sharedPool.getQueuedSubmissionCount() + sharedPool.getQueuedTaskCount());
        }

        private void rebalanceSafely() {
            try {
                rebalancePrivate();
                rebalanceShared();
            } catch (Throwable ignored) {
                // Scheduling telemetry/tuning must never take down actor work.
            }
        }

        private void rebalancePrivate() {
            if (virtualScheduler == null) return;
            int current = virtualScheduler.getParallelism();
            long queued = Math.max(0L, virtualScheduler.getQueuedVirtualThreadCount());
            int mounted = Math.max(0, virtualScheduler.getMountedVirtualThreadCount());

            int desired = current;
            if (queued > 0 && current < config.privateCarrierMax()) {
                privateIdleTicks = 0;
                desired = Math.min(config.privateCarrierMax(), current + SCALE_STEP);
            } else if (queued == 0 && mounted < current / 2) {
                privateIdleTicks++;
                if (privateIdleTicks >= 8 && current > config.privateCarrierMin()) {
                    desired = Math.max(config.privateCarrierMin(), current - SCALE_STEP);
                    privateIdleTicks = 0;
                }
            } else {
                privateIdleTicks = 0;
            }

            if (desired != current) virtualScheduler.setParallelism(desired);
        }

        private void rebalanceShared() {
            int current = sharedPool.getParallelism();
            long queued = sharedPool.getQueuedSubmissionCount() + sharedPool.getQueuedTaskCount();
            int active = sharedPool.getActiveThreadCount();

            int desired = current;
            if (queued > current && current < config.sharedPoolMax()) {
                sharedIdleTicks = 0;
                desired = Math.min(config.sharedPoolMax(), current + SCALE_STEP);
            } else if (queued == 0 && active < current / 2) {
                sharedIdleTicks++;
                if (sharedIdleTicks >= 8 && current > config.sharedPoolMin()) {
                    desired = Math.max(config.sharedPoolMin(), current - SCALE_STEP);
                    sharedIdleTicks = 0;
                }
            } else {
                sharedIdleTicks = 0;
            }

            if (desired != current) sharedPool.setParallelism(desired);
        }

        private static void setDefaultProperty(String name, String value) {
            if (System.getProperty(name) == null) {
                System.setProperty(name, value);
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private final class ActorCell<M> {
        private static final Object STOP = new Object();
        private static final int SHARED_TURN_MESSAGE_BUDGET = 64;

        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final MemoryPolicy memoryPolicy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final Consumer<Throwable> onTerminated;
        private final BlockingQueue<Object> mailbox;
        private final AtomicBoolean sharedTurnScheduled = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean terminationNotified = new AtomicBoolean();

        private volatile Thread privateActorThread;
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
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
        }

        private void start() {
            if (memoryPolicy.mode() == MemoryMode.PRIVATE_ARENA) {
                // One virtual Thread identity per private actor is deliberate:
                // Arena.ofConfined() keys ownership to this Thread identity.
                // The JVM may mount this virtual Thread on many different
                // carrier platform threads without weakening confinement.
                privateActorThread = Thread.ofVirtual()
                        .name("ores-private-actor-" + ref.id().value())
                        .start(this::runPrivateActor);
            }
        }

        private void messageAvailable() {
            if (memoryPolicy.mode() == MemoryMode.SHARED_HEAP) {
                scheduleSharedTurn();
            }
        }

        @SuppressWarnings("unchecked")
        private void runPrivateActor() {
            Throwable terminalFailure = null;
            try (ActorMemory memory = openActorMemory(memoryPolicy, policy)) {
                Behavior<M> behavior = behaviorFactory.get();
                ActorContext<M> context = newContext(memory);
                try {
                    while (!stopped.get()) {
                        Object message = mailbox.take();
                        if (message == STOP) return;
                        behavior.onMessage((M) message, context);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable failure) {
                    terminalFailure = failure;
                    stopped.set(true);
                    // v0 fail-stop supervision policy. A later supervisor layer
                    // will expose restart/escalation strategies.
                }
            } catch (Throwable failure) {
                terminalFailure = failure;
                stopped.set(true);
            } finally {
                actors.remove(ref.id(), this);
                notifyTerminated(terminalFailure);
            }
        }

        private void scheduleSharedTurn() {
            if (stopped.get()) return;
            if (sharedTurnScheduled.compareAndSet(false, true)) {
                PROCESS_SCHEDULERS.executeShared(this::runSharedTurn);
            }
        }

        @SuppressWarnings("unchecked")
        private void runSharedTurn() {
            boolean reschedule = false;
            try {
                Behavior<M> behavior = sharedBehavior;
                ActorContext<M> context = sharedContext;
                if (behavior == null) {
                    behavior = behaviorFactory.get();
                    context = newContext(SharedHeapMemory.INSTANCE);
                    sharedBehavior = behavior;
                    sharedContext = context;
                }

                int processed = 0;
                while (processed < SHARED_TURN_MESSAGE_BUDGET) {
                    Object message = mailbox.poll();
                    if (message == null) break;
                    if (message == STOP) {
                        stopped.set(true);
                        actors.remove(ref.id(), this);
                        notifyTerminated(null);
                        return;
                    }
                    behavior.onMessage((M) message, context);
                    processed++;
                }
                reschedule = !mailbox.isEmpty();
            } catch (Throwable failure) {
                stopped.set(true);
                actors.remove(ref.id(), this);
                notifyTerminated(failure);
                // v0 fail-stop supervision policy.
            } finally {
                sharedTurnScheduled.set(false);
                if (!stopped.get() && (reschedule || !mailbox.isEmpty())) {
                    scheduleSharedTurn();
                }
            }
        }

        private ActorContext<M> newContext(ActorMemory memory) {
            return new ActorContext<>() {
                @Override public ActorRef<M> self() { return ref; }
                @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                @Override public IsolatePolicy policy() { return policy; }
                @Override public ActorMemory memory() { return memory; }
            };
        }

        private void notifyTerminated(Throwable failure) {
            if (terminationNotified.compareAndSet(false, true)) {
                onTerminated.accept(failure);
            }
        }

        private void stop() {
            if (stopped.get()) return;
            if (!mailbox.offer(STOP)) {
                stopped.set(true);
                actors.remove(ref.id(), this);
                notifyTerminated(null);
            }

            if (memoryPolicy.mode() == MemoryMode.PRIVATE_ARENA) {
                Thread t = privateActorThread;
                if (t != null) t.interrupt();
            } else {
                scheduleSharedTurn();
            }
        }
    }

}
