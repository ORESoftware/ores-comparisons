package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reference runtime for bounded Oreslang channels.
 *
 * <p>This is intentionally correctness-first. Channels belonging to one
 * ChannelRuntime share a fair coordination lock so send/receive/select commits
 * are atomic and cancellation cannot race a claimed waiter. A later optimized
 * backend may shard this lock or use lock-free queues, but must preserve these
 * semantics.</p>
 *
 * <p>This class is a scheduling primitive, not an ownership bypass. Compiler
 * lowering is responsible for proving/performing Oreslang move/copy/share and
 * sendability rules before a value reaches this runtime.</p>
 */
public final class ChannelRuntime {
    public record Limits(
            int maxChannelCapacity,
            int maxSelectCases,
            int maxWaitersPerChannel) {
        public Limits {
            if (maxChannelCapacity < 0) throw new IllegalArgumentException("maxChannelCapacity must be >= 0");
            if (maxSelectCases <= 0) throw new IllegalArgumentException("maxSelectCases must be > 0");
            if (maxWaitersPerChannel <= 0) throw new IllegalArgumentException("maxWaitersPerChannel must be > 0");
        }

        public static Limits defaults() {
            return new Limits(65_536, 1_024, 65_536);
        }
    }

    private final ReentrantLock coordination = new ReentrantLock(true);
    private final Limits limits;

    public ChannelRuntime() {
        this(Limits.defaults());
    }

    public ChannelRuntime(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Limits limits() {
        return limits;
    }

    public <T> Channel<T> channel(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("channel capacity must be >= 0");
        if (capacity > limits.maxChannelCapacity()) {
            throw new IllegalArgumentException(
                    "channel capacity " + capacity + " exceeds runtime maximum " + limits.maxChannelCapacity());
        }
        return new Channel<>(this, capacity);
    }

    public record TrySendResult<T>(boolean sent, T unsent) {
        public TrySendResult {
            if (sent && unsent != null) {
                throw new IllegalArgumentException("successful try_send cannot return an unsent value");
            }
            if (!sent && unsent == null) {
                throw new IllegalArgumentException("failed try_send must return ownership of the unsent value");
            }
        }

        public static <T> TrySendResult<T> success() {
            return new TrySendResult<>(true, null);
        }

        public static <T> TrySendResult<T> full(T value) {
            return new TrySendResult<>(false, Objects.requireNonNull(value, "channel values cannot be null"));
        }
    }

    public record TryReceiveResult<T>(boolean received, T value) {
        public TryReceiveResult {
            if (!received && value != null) {
                throw new IllegalArgumentException("empty try_receive cannot carry a value");
            }
        }

        public static <T> TryReceiveResult<T> empty() {
            return new TryReceiveResult<>(false, null);
        }

        public static <T> TryReceiveResult<T> received(T value) {
            return new TryReceiveResult<>(true, Objects.requireNonNull(value, "channel values cannot be null"));
        }
    }

    public sealed interface SelectCase permits ReceiveCase, SendCase {
        Channel<?> channel();
    }

    public record ReceiveCase<T>(Channel<T> channel) implements SelectCase {
        public ReceiveCase {
            Objects.requireNonNull(channel, "select receive channel");
        }
    }

    public record SendCase<T>(Channel<T> channel, T value) implements SelectCase {
        public SendCase {
            Objects.requireNonNull(channel, "select send channel");
            Objects.requireNonNull(value, "channel values cannot be null");
        }
    }

    /**
     * caseIndex is -1 only for a default branch.
     * receivedValue is non-null only for a committed receive case.
     */
    public record SelectResult(int caseIndex, Object receivedValue, boolean defaulted) {
        public SelectResult {
            if (defaulted && caseIndex != -1) {
                throw new IllegalArgumentException("default select result must use caseIndex=-1");
            }
            if (!defaulted && caseIndex < 0) {
                throw new IllegalArgumentException("committed select case index must be >= 0");
            }
        }

        public static SelectResult defaultResult() {
            return new SelectResult(-1, null, true);
        }

        public static SelectResult send(int index) {
            return new SelectResult(index, null, false);
        }

        public static SelectResult receive(int index, Object value) {
            return new SelectResult(index, Objects.requireNonNull(value), false);
        }
    }

    /**
     * Atomically choose one ready send/receive case.
     *
     * @param startIndex rotating fairness cursor supplied by the actor frame
     * @param hasDefault if true, return default immediately when no case is ready
     */
    public CompletionStage<SelectResult> select(
            List<? extends SelectCase> cases,
            boolean hasDefault,
            int startIndex) {
        Objects.requireNonNull(cases, "select cases");
        if (cases.isEmpty()) {
            if (hasDefault) return CompletableFuture.completedFuture(SelectResult.defaultResult());
            throw new IllegalArgumentException("blocking select requires at least one case");
        }
        if (cases.size() > limits.maxSelectCases()) {
            throw new IllegalArgumentException(
                    "select case count " + cases.size() + " exceeds runtime maximum " + limits.maxSelectCases());
        }

        ArrayList<SelectNode> nodes = new ArrayList<>(cases.size());
        SelectTicket ticket = new SelectTicket();

        for (int i = 0; i < cases.size(); i++) {
            SelectCase selectCase = Objects.requireNonNull(cases.get(i), "select case");
            requireOwned(selectCase.channel());
            if (selectCase instanceof ReceiveCase<?> receive) {
                nodes.add(new ReceiveNode(receive.channel(), ticket, i));
            } else if (selectCase instanceof SendCase<?> send) {
                nodes.add(new SendNode(send.channel(), send.value(), ticket, i));
            } else {
                throw new IllegalArgumentException("unknown select case " + selectCase);
            }
        }

        SelectFuture future = new SelectFuture(ticket);
        ticket.future = future;

        List<Runnable> completions = new ArrayList<>();
        coordination.lock();
        try {
            int size = nodes.size();
            int first = Math.floorMod(startIndex, size);
            for (int offset = 0; offset < size; offset++) {
                SelectNode node = nodes.get((first + offset) % size);
                if (node instanceof SendNode send) {
                    if (tryCommitSendNode(send, completions)) break;
                } else if (node instanceof ReceiveNode receive) {
                    if (tryCommitReceiveNode(receive, completions)) break;
                }
            }

            if (ticket.state == TicketState.WAITING) {
                if (hasDefault) {
                    ticket.state = TicketState.WON;
                    completions.add(() -> future.completeDirect(SelectResult.defaultResult()));
                } else {
                    requireSelectWaiterCapacity(nodes);
                    for (SelectNode node : nodes) registerSelectNode(ticket, node);
                }
            }
        } finally {
            coordination.unlock();
        }
        runCompletions(completions);
        return future;
    }

    private void requireOwned(Channel<?> channel) {
        if (channel.runtime != this) {
            throw new IllegalArgumentException("select cannot combine channels from different ChannelRuntime instances");
        }
    }

    public static final class Channel<T> {
        private final ChannelRuntime runtime;
        private final int capacity;
        private final ArrayDeque<Object> buffer = new ArrayDeque<>();
        private final ArrayDeque<SendNode> senders = new ArrayDeque<>();
        private final ArrayDeque<ReceiveNode> receivers = new ArrayDeque<>();

        private Channel(ChannelRuntime runtime, int capacity) {
            this.runtime = runtime;
            this.capacity = capacity;
        }

        public int capacity() { return capacity; }

        public int buffered() {
            runtime.coordination.lock();
            try {
                return buffer.size();
            } finally {
                runtime.coordination.unlock();
            }
        }

        public int waitingSenders() {
            runtime.coordination.lock();
            try {
                runtime.pruneSenders(this);
                return senders.size();
            } finally {
                runtime.coordination.unlock();
            }
        }

        public int waitingReceivers() {
            runtime.coordination.lock();
            try {
                runtime.pruneReceivers(this);
                return receivers.size();
            } finally {
                runtime.coordination.unlock();
            }
        }

        /**
         * Suspends logically until the value commits to a receiver/buffer.
         * Cancellation succeeds only before commit; once claimed it returns false.
         */
        public CompletionStage<Void> send(T value) {
            Objects.requireNonNull(value, "channel values cannot be null");
            return runtime.send(this, value);
        }

        /**
         * Immediate send. Failure returns ownership of the original value.
         */
        public TrySendResult<T> trySend(T value) {
            Objects.requireNonNull(value, "channel values cannot be null");
            return runtime.trySend(this, value);
        }

        /**
         * Suspends logically until a value commits to this receiver.
         * Cancellation succeeds only before commit.
         */
        public CompletionStage<T> receive() {
            return runtime.receive(this);
        }

        /** Immediate receive; empty never consumes or registers a waiter. */
        public TryReceiveResult<T> tryReceive() {
            return runtime.tryReceive(this);
        }
    }

    private <T> CompletionStage<Void> send(Channel<T> channel, T value) {
        List<Runnable> completions = new ArrayList<>();
        SendNode node = new SendNode(channel, value, null, -1);
        WaitFuture<Void> future = new WaitFuture<>(() -> cancelOrdinary(node));
        node.future = future;

        coordination.lock();
        try {
            if (!tryCommitSendNode(node, completions)) {
                requireWaiterCapacity(channel, 1);
                channel.senders.addLast(node);
                node.registered = true;
            }
        } finally {
            coordination.unlock();
        }
        runCompletions(completions);
        return future;
    }

    private <T> TrySendResult<T> trySend(Channel<T> channel, T value) {
        List<Runnable> completions = new ArrayList<>();
        boolean sent;
        coordination.lock();
        try {
            ReceiveNode receiver = pollEligibleReceiver(channel, null);
            if (receiver != null) {
                claimReceive(receiver, value, completions);
                sent = true;
            } else if (channel.buffer.size() < channel.capacity) {
                channel.buffer.addLast(value);
                sent = true;
            } else {
                sent = false;
            }
        } finally {
            coordination.unlock();
        }
        runCompletions(completions);
        return sent ? TrySendResult.success() : TrySendResult.full(value);
    }

    @SuppressWarnings("unchecked")
    private <T> CompletionStage<T> receive(Channel<T> channel) {
        List<Runnable> completions = new ArrayList<>();
        Object immediate = NO_VALUE;
        ReceiveNode node = null;

        coordination.lock();
        try {
            if (!channel.buffer.isEmpty()) {
                immediate = channel.buffer.removeFirst();
                fillBufferFromSender(channel, completions);
            } else {
                SendNode sender = pollEligibleSender(channel, null);
                if (sender != null) {
                    immediate = sender.value;
                    claimSend(sender, completions);
                } else {
                    ReceiveNode waitingNode = new ReceiveNode(channel, null, -1);
                    WaitFuture<Object> future = new WaitFuture<>(() -> cancelOrdinary(waitingNode));
                    waitingNode.future = future;
                    requireWaiterCapacity(channel, 1);
                    channel.receivers.addLast(waitingNode);
                    waitingNode.registered = true;
                    node = waitingNode;
                }
            }
        } finally {
            coordination.unlock();
        }

        runCompletions(completions);
        if (immediate != NO_VALUE) return CompletableFuture.completedFuture((T) immediate);
        ReceiveNode waiting = node;
        @SuppressWarnings("unchecked")
        CompletionStage<T> result = (CompletionStage<T>) (CompletionStage<?>) waiting.future;
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> TryReceiveResult<T> tryReceive(Channel<T> channel) {
        List<Runnable> completions = new ArrayList<>();
        Object value = NO_VALUE;
        coordination.lock();
        try {
            if (!channel.buffer.isEmpty()) {
                value = channel.buffer.removeFirst();
                fillBufferFromSender(channel, completions);
            } else {
                SendNode sender = pollEligibleSender(channel, null);
                if (sender != null) {
                    value = sender.value;
                    claimSend(sender, completions);
                }
            }
        } finally {
            coordination.unlock();
        }
        runCompletions(completions);
        return value == NO_VALUE ? TryReceiveResult.empty() : TryReceiveResult.received((T) value);
    }

    private boolean tryCommitSendNode(SendNode sender, List<Runnable> completions) {
        if (!eligible(sender)) return false;
        Channel<?> channel = sender.channel;
        ReceiveNode receiver = pollEligibleReceiver(channel, sender.ticket);
        if (receiver != null) {
            if (!claimPair(sender, receiver, completions)) {
                // A stale/select-conflicting waiter may have raced logically;
                // retry from the next eligible receiver.
                return tryCommitSendNode(sender, completions);
            }
            return true;
        }
        if (channel.buffer.size() < channel.capacity) {
            if (!claimSend(sender, completions)) return false;
            channel.buffer.addLast(sender.value);
            return true;
        }
        return false;
    }

    private boolean tryCommitReceiveNode(ReceiveNode receiver, List<Runnable> completions) {
        if (!eligible(receiver)) return false;
        Channel<?> channel = receiver.channel;
        if (!channel.buffer.isEmpty()) {
            Object value = channel.buffer.peekFirst();
            if (!claimReceive(receiver, value, completions)) return false;
            channel.buffer.removeFirst();
            fillBufferFromSender(channel, completions);
            return true;
        }

        SendNode sender = pollEligibleSender(channel, receiver.ticket);
        if (sender != null) {
            if (!claimPair(sender, receiver, completions)) {
                return tryCommitReceiveNode(receiver, completions);
            }
            return true;
        }
        return false;
    }

    private void fillBufferFromSender(Channel<?> channel, List<Runnable> completions) {
        while (channel.buffer.size() < channel.capacity) {
            SendNode sender = pollEligibleSender(channel, null);
            if (sender == null) return;
            if (!claimSend(sender, completions)) continue;
            channel.buffer.addLast(sender.value);
        }
    }

    private ReceiveNode pollEligibleReceiver(Channel<?> channel, SelectTicket exclude) {
        Iterator<ReceiveNode> iterator = channel.receivers.iterator();
        while (iterator.hasNext()) {
            ReceiveNode receiver = iterator.next();
            if (!eligible(receiver)) {
                iterator.remove();
                receiver.registered = false;
                continue;
            }
            if (exclude != null && receiver.ticket == exclude) continue;
            iterator.remove();
            receiver.registered = false;
            return receiver;
        }
        return null;
    }

    private SendNode pollEligibleSender(Channel<?> channel, SelectTicket exclude) {
        Iterator<SendNode> iterator = channel.senders.iterator();
        while (iterator.hasNext()) {
            SendNode sender = iterator.next();
            if (!eligible(sender)) {
                iterator.remove();
                sender.registered = false;
                continue;
            }
            if (exclude != null && sender.ticket == exclude) continue;
            iterator.remove();
            sender.registered = false;
            return sender;
        }
        return null;
    }

    private void pruneSenders(Channel<?> channel) {
        Iterator<SendNode> iterator = channel.senders.iterator();
        while (iterator.hasNext()) {
            SendNode sender = iterator.next();
            if (!eligible(sender)) {
                iterator.remove();
                sender.registered = false;
            }
        }
    }

    private void pruneReceivers(Channel<?> channel) {
        Iterator<ReceiveNode> iterator = channel.receivers.iterator();
        while (iterator.hasNext()) {
            ReceiveNode receiver = iterator.next();
            if (!eligible(receiver)) {
                iterator.remove();
                receiver.registered = false;
            }
        }
    }

    private boolean eligible(WaitNode node) {
        if (node.ticket != null) return node.ticket.state == TicketState.WAITING;
        return node.state == WaitState.WAITING;
    }

    private boolean claimSend(SendNode sender, List<Runnable> completions) {
        if (sender.ticket != null) {
            if (!win(sender.ticket, sender.caseIndex, null, false, completions)) return false;
            sender.state = WaitState.CLAIMED;
            return true;
        }
        if (sender.state != WaitState.WAITING) return false;
        sender.state = WaitState.CLAIMED;
        if (sender.future != null) completions.add(() -> sender.future.completeDirect(null));
        return true;
    }

    private boolean claimReceive(ReceiveNode receiver, Object value, List<Runnable> completions) {
        if (receiver.ticket != null) {
            if (!win(receiver.ticket, receiver.caseIndex, value, true, completions)) return false;
            receiver.state = WaitState.CLAIMED;
            return true;
        }
        if (receiver.state != WaitState.WAITING) return false;
        receiver.state = WaitState.CLAIMED;
        if (receiver.future != null) completions.add(() -> receiver.future.completeDirect(value));
        return true;
    }

    private boolean claimPair(SendNode sender, ReceiveNode receiver, List<Runnable> completions) {
        if (sender.ticket != null && sender.ticket == receiver.ticket) return false;
        if (!eligible(sender) || !eligible(receiver)) return false;

        // Win both select tickets before exposing either completion. The global
        // coordination lock makes this an atomic rendezvous commit.
        if (sender.ticket != null && sender.ticket.state != TicketState.WAITING) return false;
        if (receiver.ticket != null && receiver.ticket.state != TicketState.WAITING) return false;

        if (sender.ticket != null) {
            sender.ticket.state = TicketState.WON;
            sender.ticket.winningCase = sender.caseIndex;
        } else {
            sender.state = WaitState.CLAIMED;
        }

        if (receiver.ticket != null) {
            receiver.ticket.state = TicketState.WON;
            receiver.ticket.winningCase = receiver.caseIndex;
        } else {
            receiver.state = WaitState.CLAIMED;
        }

        if (sender.ticket != null) {
            unregisterTicket(sender.ticket);
            SelectFuture future = sender.ticket.future;
            int index = sender.caseIndex;
            completions.add(() -> future.completeDirect(SelectResult.send(index)));
        } else if (sender.future != null) {
            completions.add(() -> sender.future.completeDirect(null));
        }

        if (receiver.ticket != null) {
            unregisterTicket(receiver.ticket);
            SelectFuture future = receiver.ticket.future;
            int index = receiver.caseIndex;
            Object value = sender.value;
            completions.add(() -> future.completeDirect(SelectResult.receive(index, value)));
        } else if (receiver.future != null) {
            Object value = sender.value;
            completions.add(() -> receiver.future.completeDirect(value));
        }
        return true;
    }

    private boolean win(
            SelectTicket ticket,
            int caseIndex,
            Object received,
            boolean receive,
            List<Runnable> completions) {
        if (ticket.state != TicketState.WAITING) return false;
        ticket.state = TicketState.WON;
        ticket.winningCase = caseIndex;
        unregisterTicket(ticket);
        SelectFuture future = ticket.future;
        if (receive) {
            completions.add(() -> future.completeDirect(SelectResult.receive(caseIndex, received)));
        } else {
            completions.add(() -> future.completeDirect(SelectResult.send(caseIndex)));
        }
        return true;
    }

    private void requireSelectWaiterCapacity(List<SelectNode> nodes) {
        Map<Channel<?>, Integer> additions = new IdentityHashMap<>();
        for (SelectNode node : nodes) additions.merge(node.channel, 1, Integer::sum);
        for (Map.Entry<Channel<?>, Integer> entry : additions.entrySet()) {
            requireWaiterCapacity(entry.getKey(), entry.getValue());
        }
    }

    private void requireWaiterCapacity(Channel<?> channel, int additional) {
        pruneSenders(channel);
        pruneReceivers(channel);
        long pending = (long) channel.senders.size() + channel.receivers.size();
        if (pending + additional > limits.maxWaitersPerChannel()) {
            throw new IllegalStateException(
                    "channel pending waiter limit exceeded: pending=" + pending
                            + " requested=" + additional
                            + " limit=" + limits.maxWaitersPerChannel());
        }
    }

    private void registerSelectNode(SelectTicket ticket, SelectNode node) {
        node.registered = true;
        ticket.registrations.add(node);
        if (node instanceof SendNode send) {
            send.channel.senders.addLast(send);
        } else if (node instanceof ReceiveNode receive) {
            receive.channel.receivers.addLast(receive);
        } else {
            throw new IllegalStateException("unknown select node");
        }
    }

    private void unregisterTicket(SelectTicket ticket) {
        for (SelectNode node : ticket.registrations) {
            if (!node.registered) continue;
            if (node instanceof SendNode send) {
                send.channel.senders.remove(send);
            } else if (node instanceof ReceiveNode receive) {
                receive.channel.receivers.remove(receive);
            }
            node.registered = false;
        }
        ticket.registrations.clear();
    }

    private boolean cancelOrdinary(WaitNode node) {
        boolean cancelled = false;
        coordination.lock();
        try {
            if (node == null || node.state != WaitState.WAITING) return false;
            node.state = WaitState.CANCELLED;
            if (node.registered) {
                if (node instanceof SendNode send) send.channel.senders.remove(send);
                else if (node instanceof ReceiveNode receive) receive.channel.receivers.remove(receive);
                node.registered = false;
            }
            cancelled = true;
        } finally {
            coordination.unlock();
        }
        if (cancelled) {
            if (node instanceof SendNode send && send.future != null) return send.future.cancelDirect();
            if (node instanceof ReceiveNode receive && receive.future != null) return receive.future.cancelDirect();
        }
        return false;
    }

    private boolean cancelSelect(SelectTicket ticket) {
        boolean cancelled = false;
        coordination.lock();
        try {
            if (ticket.state != TicketState.WAITING) return false;
            ticket.state = TicketState.CANCELLED;
            unregisterTicket(ticket);
            cancelled = true;
        } finally {
            coordination.unlock();
        }
        return cancelled && ticket.future.cancelDirect();
    }

    private static void runCompletions(List<Runnable> completions) {
        for (Runnable completion : completions) completion.run();
    }

    private enum WaitState { WAITING, CLAIMED, CANCELLED }
    private enum TicketState { WAITING, WON, CANCELLED }

    private static class WaitNode {
        final Channel<?> channel;
        final SelectTicket ticket;
        final int caseIndex;
        WaitState state = WaitState.WAITING;
        boolean registered;

        WaitNode(Channel<?> channel, SelectTicket ticket, int caseIndex) {
            this.channel = channel;
            this.ticket = ticket;
            this.caseIndex = caseIndex;
        }
    }

    private sealed static class SelectNode extends WaitNode permits SendNode, ReceiveNode {
        SelectNode(Channel<?> channel, SelectTicket ticket, int caseIndex) {
            super(channel, ticket, caseIndex);
        }
    }

    private static final class SendNode extends SelectNode {
        final Object value;
        WaitFuture<Void> future;

        SendNode(Channel<?> channel, Object value, SelectTicket ticket, int caseIndex) {
            super(channel, ticket, caseIndex);
            this.value = Objects.requireNonNull(value);
        }
    }

    private static final class ReceiveNode extends SelectNode {
        WaitFuture<Object> future;

        ReceiveNode(Channel<?> channel, SelectTicket ticket, int caseIndex) {
            super(channel, ticket, caseIndex);
        }
    }

    private static final class SelectTicket {
        TicketState state = TicketState.WAITING;
        int winningCase = -1;
        SelectFuture future;
        final Set<SelectNode> registrations = new LinkedHashSet<>();
    }

    private abstract static class RuntimeFuture<V> extends CompletableFuture<V> {
        @Override
        public final boolean complete(V value) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        @Override
        public final boolean completeExceptionally(Throwable failure) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        @Override
        public final void obtrudeValue(V value) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        @Override
        public final void obtrudeException(Throwable failure) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        @Override
        public final CompletableFuture<V> completeAsync(
                java.util.function.Supplier<? extends V> supplier) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        @Override
        public final CompletableFuture<V> completeAsync(
                java.util.function.Supplier<? extends V> supplier,
                java.util.concurrent.Executor executor) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        @Override
        public final CompletableFuture<V> completeOnTimeout(V value, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        @Override
        public final CompletableFuture<V> orTimeout(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("channel completion is runtime-owned");
        }

        final boolean completeDirect(V value) {
            return super.complete(value);
        }

        final boolean completeExceptionallyDirect(Throwable failure) {
            return super.completeExceptionally(failure);
        }

        final boolean cancelDirect() {
            return super.cancel(false);
        }
    }

    private final class WaitFuture<V> extends RuntimeFuture<V> {
        private final BooleanSupplier cancelAction;

        private WaitFuture(BooleanSupplier cancelAction) {
            this.cancelAction = cancelAction;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelAction.getAsBoolean();
        }
    }

    private final class SelectFuture extends RuntimeFuture<SelectResult> {
        private final SelectTicket ticket;

        private SelectFuture(SelectTicket ticket) {
            this.ticket = ticket;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelSelect(ticket);
        }
    }

    private static final Object NO_VALUE = new Object();
}
