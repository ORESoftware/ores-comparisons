package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Logical guest-language async stack metadata.
 *
 * <p>This is deliberately independent from the physical carrier stack. An
 * Oreslang task may suspend, fully unwind the Java/Truffle carrier stack, and
 * later resume on another carrier while retaining the same logical trace.</p>
 *
 * <p>The trace is bounded. Proper tail-await transfer therefore stays
 * constant-space even when a recursive computation performs millions of
 * logical calls. Repeated identical tail transfers are run-length compressed.</p>
 */
public final class OresAsyncTrace {
    private static final int DEFAULT_MAX_EVENTS = 96;
    private static final int MAX_ATTACHED_TRACES = 32;
    private static final int MAX_SYMBOL_CHARS = 256;
    private static final int MAX_SOURCE_CHARS = 4096;
    private static final int MAX_GENERATION_CHARS = 256;
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    private OresAsyncTrace() { }

    public enum BoundaryKind {
        CALL,
        AWAIT,
        TAIL_AWAIT,
        ACTOR_MESSAGE,
        TASK_SPAWN,
        SELECT,
        CHANNEL_SEND,
        CHANNEL_RECEIVE,
        STREAM_RESUME,
        GENERATOR_RESUME,
        RPC,
        SUPERVISOR_CONTROL,
        CANCELLATION,
        TIMEOUT,
        CALLBACK_ADAPTER,
        IO_COMPLETION
    }

    public record SourceSite(
            String sourceId,
            int line,
            int column,
            String codeGenerationId) {
        public SourceSite {
            sourceId = sourceId == null || sourceId.isBlank() ? "<unknown>" : sourceId;
        }

        public static SourceSite unknown(String sourceId, String codeGenerationId) {
            return new SourceSite(sourceId, -1, -1, codeGenerationId);
        }

        public boolean known() {
            return line > 0 && column > 0;
        }
    }

    public record Frame(String symbol, SourceSite site) {
        public Frame {
            if (symbol == null || symbol.isBlank()) symbol = "<anonymous>";
            site = Objects.requireNonNull(site, "site");
        }
    }

    public sealed interface Event permits CallEvent, AwaitEvent, TailEvent, BoundaryEvent {
        SourceSite site();
    }

    public record CallEvent(Frame caller, Frame callee, SourceSite site)
            implements Event {
        public CallEvent {
            Objects.requireNonNull(caller, "caller");
            Objects.requireNonNull(callee, "callee");
            Objects.requireNonNull(site, "site");
        }
    }

    public record AwaitEvent(Frame frame, SourceSite site)
            implements Event {
        public AwaitEvent {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(site, "site");
        }
    }

    public record BoundaryEvent(
            BoundaryKind kind,
            Frame frame,
            SourceSite site) implements Event {
        public BoundaryEvent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(site, "site");
        }
    }

    public record TailEvent(
            Frame from,
            Frame to,
            SourceSite site,
            long repetitions) implements Event {
        public TailEvent {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(site, "site");
            if (repetitions <= 0) {
                throw new IllegalArgumentException("tail repetition count must be positive");
            }
        }

        private boolean sameTransition(Frame from, Frame to, SourceSite site) {
            return this.from.equals(from) && this.to.equals(to) && this.site.equals(site);
        }

        private TailEvent incremented() {
            if (repetitions == Long.MAX_VALUE) return this;
            return new TailEvent(from, to, site, repetitions + 1);
        }
    }

    /**
     * Mutable task-local trace. One Ores task/actor turn owns a Trace at a time,
     * so mutation follows the same single-executor lease as guest execution.
     */
    public static final class Trace {
        private final int maxEvents;
        private final ArrayDeque<Event> history;
        private final Object identity = new Object();
        private Frame current;
        private long elidedEvents;
        private long revision;

        private Trace(Frame current, int maxEvents) {
            this.current = Objects.requireNonNull(current, "current");
            if (maxEvents <= 0) throw new IllegalArgumentException("maxEvents must be positive");
            this.maxEvents = maxEvents;
            this.history = new ArrayDeque<>(Math.min(maxEvents, 16));
        }

        private Trace(Trace parent, Frame child, SourceSite callSite) {
            this.current = Objects.requireNonNull(child, "child");
            this.maxEvents = parent.maxEvents;
            this.history = new ArrayDeque<>(parent.history);
            this.elidedEvents = parent.elidedEvents;
            this.revision = parent.revision;
            addBounded(new CallEvent(parent.current, child, callSite));
        }

        public Frame currentFrame() {
            return current;
        }

        public long elidedEvents() {
            return elidedEvents;
        }

        public int retainedEventCount() {
            return history.size();
        }

        public List<Event> events() {
            return List.copyOf(history);
        }

        TraceSnapshot snapshot() {
            return new TraceSnapshot(current, List.copyOf(history), elidedEvents, revision);
        }

        Object identity() {
            return identity;
        }

        public Trace child(Frame child, SourceSite callSite) {
            return new Trace(this, child, Objects.requireNonNull(callSite, "callSite"));
        }

        public void awaitAt(SourceSite site) {
            addBounded(new AwaitEvent(current, Objects.requireNonNull(site, "site")));
        }

        public void boundary(BoundaryKind kind, SourceSite site) {
            addBounded(new BoundaryEvent(
                    Objects.requireNonNull(kind, "kind"),
                    current,
                    Objects.requireNonNull(site, "site")));
        }

        public void tailAwait(Frame target, SourceSite site) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(site, "site");

            Event last = history.peekLast();
            if (last instanceof TailEvent tail && tail.sameTransition(current, target, site)) {
                history.removeLast();
                history.addLast(tail.incremented());
                bumpRevision();
            } else {
                addBounded(new TailEvent(current, target, site, 1));
            }
            current = target;
        }

        private void addBounded(Event event) {
            while (history.size() >= maxEvents) {
                history.removeFirst();
                if (elidedEvents != Long.MAX_VALUE) elidedEvents++;
            }
            history.addLast(event);
            bumpRevision();
        }

        private void bumpRevision() {
            if (revision != Long.MAX_VALUE) revision++;
        }

        public String render() {
            StringBuilder out = new StringBuilder();
            appendFrame(out, "at", current);
            List<Event> events = new ArrayList<>(history);
            for (int i = events.size() - 1; i >= 0; i--) {
                Event event = events.get(i);
                if (event instanceof AwaitEvent awaited) {
                    appendBoundary(out, "await", awaited.site(), null);
                } else if (event instanceof CallEvent call) {
                    appendFrame(out, "at", call.caller());
                } else if (event instanceof TailEvent tail) {
                    appendBoundary(
                            out,
                            "tail-await",
                            tail.site(),
                            tail.from().symbol() + " -> " + tail.to().symbol()
                                    + (tail.repetitions() == 1
                                            ? ""
                                            : " repeated " + tail.repetitions() + " times"));
                } else if (event instanceof BoundaryEvent boundary) {
                    appendBoundary(
                            out,
                            boundary.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                            boundary.site(),
                            boundary.frame().symbol());
                }
            }
            if (elidedEvents > 0) {
                out.append("\n    ... ")
                        .append(elidedEvents)
                        .append(" older async trace events elided");
            }
            return out.toString();
        }

        public StackTraceElement[] toJavaStackTrace() {
            List<StackTraceElement> result = new ArrayList<>();
            result.add(toStackTraceElement(current.symbol(), current.site()));

            List<Event> events = new ArrayList<>(history);
            for (int i = events.size() - 1; i >= 0; i--) {
                Event event = events.get(i);
                if (event instanceof AwaitEvent awaited) {
                    result.add(toStackTraceElement("<await>", awaited.site()));
                } else if (event instanceof CallEvent call) {
                    result.add(toStackTraceElement(call.caller().symbol(), call.caller().site()));
                } else if (event instanceof TailEvent tail) {
                    String label = "<tail-await "
                            + tail.from().symbol()
                            + " -> "
                            + tail.to().symbol()
                            + (tail.repetitions() == 1 ? "" : " x" + tail.repetitions())
                            + ">";
                    result.add(toStackTraceElement(label, tail.site()));
                } else if (event instanceof BoundaryEvent boundary) {
                    result.add(toStackTraceElement(
                            "<" + boundary.kind().name().toLowerCase(Locale.ROOT).replace('_', '-') + ">",
                            boundary.site()));
                }
            }

            if (elidedEvents > 0) {
                result.add(new StackTraceElement(
                        "oreslang.async",
                        "<" + elidedEvents + " older async events elided>",
                        null,
                        -1));
            }
            return result.toArray(StackTraceElement[]::new);
        }
    }

    public static Trace root(Frame frame) {
        return new Trace(Objects.requireNonNull(frame, "frame"), DEFAULT_MAX_EVENTS);
    }

    public static Trace current() {
        return CURRENT.get();
    }

    public static Trace childOfCurrent(Frame frame, SourceSite callSite) {
        Trace parent = CURRENT.get();
        if (parent == null) return root(frame);
        return parent.child(frame, callSite);
    }

    public static Scope install(Trace trace) {
        Trace installed = Objects.requireNonNull(trace, "trace");
        Trace prior = CURRENT.get();
        CURRENT.set(installed);
        return new InstalledScope(Thread.currentThread(), prior, installed);
    }

    public static Throwable attachGuest(Throwable failure, Trace trace) {
        Objects.requireNonNull(failure, "failure");
        if (trace == null) return failure;

        synchronized (failure) {
            for (Throwable suppressed : failure.getSuppressed()) {
                if (suppressed instanceof LogicalGuestStackTrace) return failure;
            }
            failure.addSuppressed(new LogicalGuestStackTrace(trace.snapshot()));
        }
        return failure;
    }

    public static Throwable attach(Throwable failure, Trace trace) {
        Objects.requireNonNull(failure, "failure");
        if (trace == null) return failure;

        /*
         * A failure may cross arbitrarily many awaiters. Bounding each Trace is
         * not enough if every boundary adds another suppressed exception, so
         * bound the number of retained causal traces as well. Synchronizing on
         * the Throwable also makes shared-Future fan-out deterministic when
         * several waiters observe the same failure concurrently.
         */
        synchronized (failure) {
            int attached = 0;
            LogicalAsyncTraceElision elision = null;
            for (Throwable suppressed : failure.getSuppressed()) {
                if (suppressed instanceof LogicalAsyncStackTrace logical) {
                    if (logical.traceIdentity == trace.identity()
                            && logical.traceRevision == trace.revision) {
                        return failure;
                    }
                    attached++;
                } else if (suppressed instanceof LogicalAsyncTraceElision marker) {
                    elision = marker;
                }
            }

            if (attached < MAX_ATTACHED_TRACES) {
                failure.addSuppressed(new LogicalAsyncStackTrace(trace));
            } else if (elision == null) {
                failure.addSuppressed(new LogicalAsyncTraceElision());
            } else {
                elision.increment();
            }
        }
        return failure;
    }

    static record TraceSnapshot(
            Frame currentFrame,
            List<Event> events,
            long elidedEvents,
            long revision) {
        TraceSnapshot {
            currentFrame = Objects.requireNonNull(currentFrame, "currentFrame");
            events = List.copyOf(events);
            if (elidedEvents < 0) {
                throw new IllegalArgumentException("elidedEvents must be non-negative");
            }
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
        }
    }

    private static String renderSnapshot(TraceSnapshot trace) {
        StringBuilder out = new StringBuilder();
        appendFrame(out, "at", trace.currentFrame());
        List<Event> events = trace.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            Event event = events.get(i);
            if (event instanceof AwaitEvent awaited) {
                appendBoundary(out, "await", awaited.site(), null);
            } else if (event instanceof CallEvent call) {
                appendFrame(out, "at", call.caller());
            } else if (event instanceof TailEvent tail) {
                appendBoundary(
                        out,
                        "tail-await",
                        tail.site(),
                        tail.from().symbol() + " -> " + tail.to().symbol()
                                + (tail.repetitions() == 1
                                        ? ""
                                        : " repeated " + tail.repetitions() + " times"));
            } else if (event instanceof BoundaryEvent boundary) {
                appendBoundary(
                        out,
                        boundary.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        boundary.site(),
                        boundary.frame().symbol());
            }
        }
        if (trace.elidedEvents() > 0) {
            out.append("\n    ... ")
                    .append(trace.elidedEvents())
                    .append(" older async trace events elided");
        }
        return out.toString();
    }

    private static StackTraceElement[] javaStackSnapshot(TraceSnapshot trace) {
        List<StackTraceElement> result = new ArrayList<>();
        result.add(toStackTraceElement(
                trace.currentFrame().symbol(),
                trace.currentFrame().site()));
        List<Event> events = trace.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            Event event = events.get(i);
            if (event instanceof AwaitEvent awaited) {
                result.add(toStackTraceElement("<await>", awaited.site()));
            } else if (event instanceof CallEvent call) {
                result.add(toStackTraceElement(
                        call.caller().symbol(),
                        call.caller().site()));
            } else if (event instanceof TailEvent tail) {
                String label = "<tail-await "
                        + tail.from().symbol()
                        + " -> "
                        + tail.to().symbol()
                        + (tail.repetitions() == 1
                                ? ""
                                : " x" + tail.repetitions())
                        + ">";
                result.add(toStackTraceElement(label, tail.site()));
            } else if (event instanceof BoundaryEvent boundary) {
                result.add(toStackTraceElement(
                        "<" + boundary.kind().name().toLowerCase(Locale.ROOT).replace('_', '-') + ">",
                        boundary.site()));
            }
        }
        if (trace.elidedEvents() > 0) {
            result.add(new StackTraceElement(
                    "oreslang.async",
                    "<" + trace.elidedEvents() + " older async events elided>",
                    null,
                    -1));
        }
        return result.toArray(StackTraceElement[]::new);
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final class InstalledScope implements Scope {
        private final Thread owner;
        private final Trace prior;
        private final Trace installed;
        private boolean closed;

        private InstalledScope(Thread owner, Trace prior, Trace installed) {
            this.owner = owner;
            this.prior = prior;
            this.installed = installed;
        }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException(
                        "async trace scope must close on the carrier thread that installed it");
            }
            if (CURRENT.get() != installed) {
                throw new IllegalStateException(
                        "async trace scopes must close in strict LIFO order");
            }
            closed = true;
            if (prior == null) CURRENT.remove();
            else CURRENT.set(prior);
        }
    }

    /**
     * One immutable deepest guest-language call stack for a synchronous failure.
     *
     * <p>Outer synchronous frames do not attach another copy because the deepest
     * snapshot already contains their call events. If the same failure later
     * crosses an await, asynchronous causal snapshots are attached separately.</p>
     */
    public static final class LogicalGuestStackTrace extends RuntimeException {
        private final TraceSnapshot snapshot;
        private final String logicalTrace;

        private LogicalGuestStackTrace(TraceSnapshot snapshot) {
            super("Oreslang logical guest stack\n" + renderSnapshot(snapshot),
                    null,
                    false,
                    true);
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.logicalTrace = renderSnapshot(snapshot);
            setStackTrace(javaStackSnapshot(snapshot));
        }

        public String logicalTrace() {
            return logicalTrace;
        }

        TraceSnapshot snapshot() {
            return snapshot;
        }
    }

    /**
     * A suppressed diagnostic exception preserves the original failure type
     * while making the logical Oreslang async stack visible to ordinary Java,
     * Polyglot, Native Image, logs, and test tooling.
     */
    public static final class LogicalAsyncStackTrace extends RuntimeException {
        private final Object traceIdentity;
        private final long traceRevision;
        private final TraceSnapshot snapshot;
        private final String logicalTrace;
        private final long elidedEvents;

        private LogicalAsyncStackTrace(Trace trace) {
            this(trace, trace.snapshot());
        }

        private LogicalAsyncStackTrace(Trace trace, TraceSnapshot snapshot) {
            super("Oreslang logical async stack\n" + renderSnapshot(snapshot),
                    null,
                    false,
                    true);
            Trace actual = Objects.requireNonNull(trace, "trace");
            this.traceIdentity = actual.identity();
            this.traceRevision = snapshot.revision();
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.logicalTrace = renderSnapshot(snapshot);
            this.elidedEvents = snapshot.elidedEvents();
            setStackTrace(javaStackSnapshot(snapshot));
        }

        public String logicalTrace() {
            return logicalTrace;
        }

        public long elidedEvents() {
            return elidedEvents;
        }

        /** Immutable failure-time view used by the canonical diagnostic renderer. */
        TraceSnapshot snapshot() {
            return snapshot;
        }
    }

    /**
     * Compact marker used once the per-failure causal-trace budget is full.
     * The counter is deliberately saturating so diagnostics cannot wrap after
     * pathological runtimes.
     */
    public static final class LogicalAsyncTraceElision extends RuntimeException {
        private long elidedTraces = 1;

        private LogicalAsyncTraceElision() {
            super(null, null, false, false);
        }

        private synchronized void increment() {
            if (elidedTraces != Long.MAX_VALUE) elidedTraces++;
        }

        public synchronized long elidedTraces() {
            return elidedTraces;
        }

        @Override
        public synchronized String getMessage() {
            return elidedTraces + " additional Oreslang logical async trace(s) elided";
        }
    }

    private static StackTraceElement toStackTraceElement(String symbol, SourceSite site) {
        String generation = site.codeGenerationId();
        String safeGeneration = safeText(generation, MAX_GENERATION_CHARS);
        String className = safeGeneration.isBlank()
                ? "oreslang.async"
                : "oreslang.async[" + safeGeneration + "]";
        return new StackTraceElement(
                className,
                safeText(symbol, MAX_SYMBOL_CHARS),
                safeText(site.sourceId(), MAX_SOURCE_CHARS),
                site.line() > 0 ? site.line() : -1);
    }


    private static String safeText(String value, int maxChars) {
        if (value == null || value.isEmpty()) return "";
        int end = Math.min(value.length(), maxChars);
        StringBuilder out = new StringBuilder(Math.min(end + 16, maxChars + 32));
        for (int i = 0; i < end; i++) {
            char ch = value.charAt(i);
            if (ch == '\n') out.append("\\n");
            else if (ch == '\r') out.append("\\r");
            else if (ch == '\t') out.append("\\t");
            else if (Character.isISOControl(ch)
                    || Character.isSurrogate(ch)
                    || isBidiControl(ch)
                    || ch == '\u2028'
                    || ch == '\u2029') {
                appendUnicodeEscape(out, ch);
            } else {
                out.append(ch);
            }
        }
        if (value.length() > maxChars) out.append('…');
        return out.toString();
    }

    private static boolean isBidiControl(char ch) {
        return ch == '\u061c'
                || (ch >= '\u200e' && ch <= '\u200f')
                || (ch >= '\u202a' && ch <= '\u202e')
                || (ch >= '\u2066' && ch <= '\u2069');
    }

    private static void appendUnicodeEscape(StringBuilder out, char ch) {
        out.append("\\u");
        String hex = Integer.toHexString(ch);
        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
        out.append(hex);
    }

    private static void appendFrame(StringBuilder out, String prefix, Frame frame) {
        out.append("\n    ").append(prefix).append(' ')
                .append(safeText(frame.symbol(), MAX_SYMBOL_CHARS));
        appendSite(out, frame.site());
    }

    private static void appendBoundary(
            StringBuilder out,
            String kind,
            SourceSite site,
            String detail) {
        out.append("\n    --- ").append(safeText(kind, MAX_SYMBOL_CHARS));
        if (detail != null && !detail.isBlank()) {
            out.append(' ').append(safeText(detail, MAX_SOURCE_CHARS));
        }
        appendSite(out, site);
        out.append(" ---");
    }

    private static void appendSite(StringBuilder out, SourceSite site) {
        out.append(" (").append(safeText(site.sourceId(), MAX_SOURCE_CHARS));
        if (site.line() > 0) {
            out.append(':').append(site.line());
            if (site.column() > 0) out.append(':').append(site.column());
        }
        if (site.codeGenerationId() != null && !site.codeGenerationId().isBlank()) {
            out.append(" @gen=")
                    .append(safeText(site.codeGenerationId(), MAX_GENERATION_CHARS));
        }
        out.append(')');
    }
}
