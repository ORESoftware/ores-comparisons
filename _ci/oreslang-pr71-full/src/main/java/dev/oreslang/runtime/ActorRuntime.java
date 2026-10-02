package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang actor state is owned by one actor. Messages pass through freeze(),
 * which only accepts values that can be made deeply immutable without leaving
 * a writable alias in the receiver. Cross-isolate transports should serialize
 * Frozen values rather than sharing Java object references.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final ThreadLocal<ActorExecution> CURRENT_ACTOR = new ThreadLocal<>();

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final Map<ActorId, Set<ActorId>> children = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final IsolatePolicy policyCeiling;

    public ActorRuntime() {
        this(IsolatePolicy.developer());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }

    public record ActorId(UUID value) {
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    private record ActorExecution(ActorRuntime runtime, ActorId actorId) { }

    public static boolean inActorExecution() {
        return CURRENT_ACTOR.get() != null;
    }

    public static ActorId currentActorIdOrNull() {
        ActorExecution current = CURRENT_ACTOR.get();
        return current == null ? null : current.actorId();
    }

    public boolean isCurrentExecutionActor() {
        ActorExecution current = CURRENT_ACTOR.get();
        return current != null && current.runtime() == this;
    }

    public ActorId currentActorId() {
        ActorExecution current = CURRENT_ACTOR.get();
        return current != null && current.runtime() == this ? current.actorId() : null;
    }

    public record Shared<T>(T value) { }

    /**
     * SHARED actors are semantically isolated but use the current JVM runtime.
     * ISOLATE actors request the stronger backend and are prevented by the
     * compiler from capturing mutable/borrowed outer state.
     */
    public enum ActorKind { SHARED, ISOLATE }

    /**
     * Internal non-failure control transfer used by the Oreslang `stop;`
     * statement. It is deliberately stackless: the actor is terminating
     * normally, not reporting an error.
     */
    public static final class StopActorSignal extends RuntimeException {
        private final List<CompletableFuture<Void>> descendantTerminations;

        private StopActorSignal(List<CompletableFuture<Void>> descendantTerminations) {
            super(null, null, false, false);
            this.descendantTerminations = List.copyOf(descendantTerminations);
        }

        private void awaitDescendants() {
            for (CompletableFuture<Void> terminated : descendantTerminations) {
                terminated.join();
            }
        }
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
    }

    public final class ActorRef<M> {
        private final ActorId id;
        private final ActorKind kind;
        private final ActorId parentId;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private volatile Thread runner;

        private ActorRef(ActorId id, ActorKind kind, ActorId parentId) {
            this.id = id;
            this.kind = kind;
            this.parentId = parentId;
        }

        public ActorId id() { return id; }
        public ActorKind kind() { return kind; }
        public ActorId parentId() { return parentId; }
        public boolean isAlive() { return alive.get(); }
        public boolean failed() { return failure.get() != null; }
        public Throwable failure() { return failure.get(); }
        public String failureTrace() {
            Throwable current = failure.get();
            return current == null ? "" : OresTrace.format(current);
        }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        /** Cooperative stop. Failure state is not set for an explicit stop. */
        public void stop() {
            ActorRuntime.this.stop(this);
        }

        /**
         * Waits for actor termination without rethrowing actor failure. This is
         * deliberate failure isolation: observing/joining a dead actor cannot
         * crash the caller unless the caller explicitly inspects/escalates it.
         */
        public void join() {
            if (Thread.currentThread() == runner) {
                throw new IllegalStateException("actor cannot join itself");
            }
            terminated.join();
        }

        @Override
        public String toString() {
            return "ActorRef[" + kind + ":" + id.value() + "]";
        }
    }

    /**
     * Creates actor-local behavior inside the actor thread. The Supplier should
     * be generated by Oreslang lowering, not supplied from untrusted guest code.
     */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(ActorKind.SHARED, policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(ActorKind kind, Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(kind, policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(ActorKind.SHARED, policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        requireWithinCeiling(policy);

        ActorId parentId = currentActorId();
        if (parentId != null) {
            ActorCell<?> parent = actors.get(parentId);
            if (parent == null || parent.stopRequested.get() || !parent.ref.accepting.get()) {
                throw new IllegalStateException("stopping actor cannot spawn child actors");
            }
        }

        ActorId id = ActorId.create();
        ActorRef<M> ref = new ActorRef<>(id, java.util.Objects.requireNonNull(kind), parentId);
        ActorCell<M> cell = new ActorCell<>(ref, policy, behaviorFactory);
        actors.put(id, cell);
        if (parentId != null) {
            children.computeIfAbsent(parentId, ignored -> ConcurrentHashMap.newKeySet()).add(id);
        }
        try {
            cell.start();
            return ref;
        } catch (RuntimeException | Error startupFailure) {
            actors.remove(id, cell);
            unlinkChild(ref);
            throw startupFailure;
        }
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
        if (cell == null || !ref.isAlive()) throw new IllegalStateException("actor is not alive " + ref.id());
        Object frozen = freeze(message);
        OresTrace.Snapshot trace = OresTrace.captureBoundary(
                "actor-send",
                ref.kind().name().toLowerCase() + ":" + ref.id().value());
        cell.enqueue(new MessageEnvelope(frozen, trace));
    }

    @SuppressWarnings("unchecked")
    public <M> void stop(ActorRef<M> ref) {
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell != null) cell.stop();
    }

    /**
     * Implements the source-level `stop;` statement. The current actor stops
     * accepting immediately; all descendants receive recursive graceful/FIFO
     * stop requests. The caller then unwinds its own finally/defer frames and
     * terminates through StopActorSignal.
     */
    public StopActorSignal beginCurrentActorTreeStop() {
        ActorExecution current = CURRENT_ACTOR.get();
        if (current == null || current.runtime() != this) {
            throw new IllegalStateException("stop requires an active actor execution");
        }
        ActorCell<?> self = actors.get(current.actorId());
        if (self == null) {
            throw new IllegalStateException("current actor is no longer registered");
        }

        self.beginImmediateStop();
        ArrayList<CompletableFuture<Void>> terminations = new ArrayList<>();
        stopDescendantsGracefully(
                current.actorId(),
                new java.util.HashSet<>(),
                terminations);
        return new StopActorSignal(terminations);
    }

    private void stopDescendantsGracefully(
            ActorId parentId,
            Set<ActorId> visited,
            List<CompletableFuture<Void>> terminations) {
        if (!visited.add(parentId)) return;
        Set<ActorId> snapshot = children.get(parentId);
        if (snapshot == null || snapshot.isEmpty()) return;
        for (ActorId childId : List.copyOf(snapshot)) {
            ActorCell<?> child = actors.get(childId);
            if (child == null) continue;

            // Mark the child stopping before traversing its children. spawn()
            // checks this flag, so the descendant set becomes stable while we
            // snapshot the rest of the tree.
            child.stop();
            terminations.add(child.ref.terminated);
            stopDescendantsGracefully(childId, visited, terminations);
        }
    }

    private void unlinkChild(ActorRef<?> ref) {
        ActorId parentId = ref.parentId();
        if (parentId != null) {
            Set<ActorId> siblings = children.get(parentId);
            if (siblings != null) {
                siblings.remove(ref.id());
                if (siblings.isEmpty()) children.remove(parentId, siblings);
            }
        }
        children.remove(ref.id());
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

    private record MessageEnvelope(Object message, OresTrace.Snapshot trace) { }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (ActorCell<?> cell : actors.values()) cell.forceStop();
        actors.clear();
    }

    private final class ActorCell<M> {
        private static final Object STOP = new Object();
        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final BlockingQueue<Object> mailbox;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private volatile Thread thread;

        private ActorCell(ActorRef<M> ref, IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
            this.ref = ref;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
        }

        private void start() {
            thread = Thread.ofVirtual()
                    .name("ores-actor-" + ref.kind().name().toLowerCase() + "-" + ref.id().value())
                    .start(this::run);
        }

        @SuppressWarnings("unchecked")
        private void run() {
            ref.runner = Thread.currentThread();
            CURRENT_ACTOR.set(new ActorExecution(ActorRuntime.this, ref.id()));
            try {
                final Behavior<M> behavior = behaviorFactory.get();
                final ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                };

                while (true) {
                    Object queued = mailbox.take();
                    if (queued == STOP) return;
                    MessageEnvelope envelope = (MessageEnvelope) queued;
                    try (OresTrace.Scope ignored = OresTrace.install(envelope.trace())) {
                        behavior.onMessage((M) envelope.message(), context);
                    }
                    if (stopRequested.get() && mailbox.isEmpty()) return;
                }
            } catch (StopActorSignal stopped) {
                // The signal carries the exact descendant termination futures
                // captured while recursively stopping the tree, so unlink races
                // cannot make parent.join() return early.
                stopped.awaitDescendants();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (CancellationException cancellation) {
                if (!closed.get() && !stopRequested.get()) {
                    ref.failure.compareAndSet(null, cancellation);
                }
            } catch (Throwable failure) {
                // Fail-stop by actor: never escape onto the spawning/main thread.
                // Preserve the logical Ores trace rather than the carrier-thread
                // stack so diagnostics survive actor/thread boundaries.
                Throwable recorded = failure instanceof RuntimeException runtimeFailure
                        ? OresTrace.attach(runtimeFailure)
                        : failure;
                ref.failure.compareAndSet(null, recorded);
            } finally {
                ref.accepting.set(false);
                ref.alive.set(false);
                actors.remove(ref.id(), this);
                unlinkChild(ref);
                CURRENT_ACTOR.remove();
                ref.terminated.complete(null);
            }
        }

        private synchronized void enqueue(Object frozen) {
            if (!ref.isAlive() || !ref.accepting.get()) {
                throw new IllegalStateException("actor is not accepting messages " + ref.id());
            }
            if (!mailbox.offer(frozen)) {
                throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
            }
        }

        private synchronized void beginImmediateStop() {
            if (!ref.isAlive()) return;
            ref.accepting.set(false);
            stopRequested.set(true);
        }

        private synchronized void stop() {
            if (!ref.isAlive() || !ref.accepting.compareAndSet(true, false)) return;
            stopRequested.set(true);
            // If the queue is full, the actor exits after draining it because
            // stopRequested is checked after every delivered message.
            mailbox.offer(STOP);
        }

        private synchronized void forceStop() {
            ref.accepting.set(false);
            stopRequested.set(true);
            mailbox.clear();
            mailbox.offer(STOP);
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }
}
