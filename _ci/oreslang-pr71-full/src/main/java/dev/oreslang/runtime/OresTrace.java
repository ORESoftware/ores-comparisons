package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Logical Oreslang stack traces are independent of JVM carrier threads.
 *
 * Frames follow language call/actor/async boundaries rather than Java thread
 * stacks, so the same representation can be captured before an async hop and
 * restored on another virtual/platform/event-loop thread.
 */
public final class OresTrace {
    public enum Mode { AUTO, FULL, OFF }

    public record Frame(String kind, String name, String actorId, String threadName) {
        public Frame {
            kind = Objects.requireNonNullElse(kind, "call");
            name = Objects.requireNonNullElse(name, "<anonymous>");
            threadName = Objects.requireNonNullElse(threadName, "<unknown>");
        }
    }

    public record Snapshot(List<Frame> frames) {
        public Snapshot { frames = List.copyOf(frames); }
        public boolean empty() { return frames.isEmpty(); }
    }

    public interface Carrier {
        Snapshot oresTrace();
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final Mode MODE = readMode(System.getenv("ORES_STACK_TRACE"));

    private OresTrace() { }

    public static Mode mode() { return MODE; }

    public static Scope enter(String kind, String name, boolean noStackTrace) {
        if (!captureEnabled(noStackTrace)) return () -> { };
        ArrayDeque<Frame> stack = FRAMES.get();
        stack.addLast(frame(kind, name));
        return () -> {
            ArrayDeque<Frame> current = FRAMES.get();
            if (!current.isEmpty()) current.removeLast();
            if (current.isEmpty()) FRAMES.remove();
        };
    }

    public static Snapshot snapshot() {
        if (MODE == Mode.OFF) return new Snapshot(List.of());
        return new Snapshot(new ArrayList<>(FRAMES.get()));
    }

    /**
     * Captures the current logical context plus an explicit discontinuity frame
     * such as async/await, event-loop dispatch, or actor transport.
     */
    public static Snapshot captureBoundary(String kind, String name) {
        if (MODE == Mode.OFF) return new Snapshot(List.of());
        ArrayList<Frame> result = new ArrayList<>(FRAMES.get());
        result.add(frame(kind, name));
        return new Snapshot(result);
    }

    public static Scope install(Snapshot snapshot) {
        if (MODE == Mode.OFF || snapshot == null || snapshot.empty()) return () -> { };
        ArrayDeque<Frame> previous = FRAMES.get();
        ArrayDeque<Frame> installed = new ArrayDeque<>(snapshot.frames());
        FRAMES.set(installed);
        return () -> {
            if (previous.isEmpty()) FRAMES.remove();
            else FRAMES.set(previous);
        };
    }

    public static Runnable wrap(Runnable task, String boundaryKind, String boundaryName) {
        Snapshot captured = captureBoundary(boundaryKind, boundaryName);
        return () -> {
            try (Scope ignored = install(captured)) {
                task.run();
            }
        };
    }

    public static <T> Supplier<T> wrap(Supplier<T> task, String boundaryKind, String boundaryName) {
        Snapshot captured = captureBoundary(boundaryKind, boundaryName);
        return () -> {
            try (Scope ignored = install(captured)) {
                return task.get();
            }
        };
    }

    public static RuntimeException attach(RuntimeException failure) {
        if (failure instanceof Carrier || MODE == Mode.OFF) return failure;
        return new TracedRuntimeException(failure, snapshot());
    }

    public static String format(Throwable failure) {
        Snapshot trace = failure instanceof Carrier carrier ? carrier.oresTrace() : null;
        if (trace == null || trace.empty()) return "";
        return format(trace);
    }

    public static String format(Snapshot trace) {
        if (trace == null || trace.empty()) return "";
        StringBuilder out = new StringBuilder("Oreslang stack trace:");
        List<Frame> frames = trace.frames();
        for (int i = frames.size() - 1; i >= 0; i--) {
            Frame frame = frames.get(i);
            out.append("\n  at ").append(frame.kind()).append(' ').append(frame.name());
            if (frame.actorId() != null) out.append(" [actor=").append(frame.actorId()).append(']');
            out.append(" [thread=").append(frame.threadName()).append(']');
        }
        return out.toString();
    }

    public static final class TracedRuntimeException extends RuntimeException implements Carrier {
        private final RuntimeException failure;
        private final Snapshot trace;

        private TracedRuntimeException(RuntimeException failure, Snapshot trace) {
            super(message(failure, trace), failure, true, false);
            this.failure = Objects.requireNonNull(failure);
            this.trace = Objects.requireNonNull(trace);
        }

        public RuntimeException failure() { return failure; }
        @Override public Snapshot oresTrace() { return trace; }

        private static String message(RuntimeException failure, Snapshot trace) {
            String base = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            String rendered = format(trace);
            return rendered.isEmpty() ? base : base + "\n" + rendered;
        }
    }

    private static boolean captureEnabled(boolean noStackTrace) {
        return switch (MODE) {
            case FULL -> true;
            case OFF -> false;
            case AUTO -> !noStackTrace;
        };
    }

    private static Frame frame(String kind, String name) {
        ActorRuntime.ActorId actorId = ActorRuntime.currentActorIdOrNull();
        return new Frame(
                kind,
                name,
                actorId == null ? null : actorId.value().toString(),
                Thread.currentThread().getName());
    }

    private static Mode readMode(String raw) {
        if (raw == null || raw.isBlank()) return Mode.AUTO;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> Mode.AUTO;
            case "1", "true", "on", "full" -> Mode.FULL;
            case "0", "false", "off", "none" -> Mode.OFF;
            default -> throw new IllegalArgumentException(
                    "invalid ORES_STACK_TRACE='" + raw + "'; expected auto, full/on, or off/none");
        };
    }
}
