package dev.oreslang.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Polyglot-boundary representation of one Oreslang failure.
 *
 * <p>The original throwable remains the semantic failure used inside Oreslang
 * and by the trusted launcher handoff. This wrapper exists only after guest
 * execution has escaped through the Polyglot boundary. It exposes immutable,
 * bounded logical stack evidence through Truffle interop without exporting the
 * original Java Throwable or carrier stack.</p>
 */
@ExportLibrary(InteropLibrary.class)
public final class OresGuestException extends AbstractTruffleException {
    private static final int MAX_TEXT_CHARS = 4096;
    private static final int MAX_SYMBOL_CHARS = 256;

    private static final MemberNames MEMBERS = new MemberNames(
            "schema",
            "name",
            "message",
            "guest_stack",
            "async_traces",
            "elided_async_traces");

    private final String errorName;
    private final String safeMessage;
    private final FrameArray guestStack;
    private final TraceArray asyncTraces;
    private final long elidedAsyncTraces;
    private final FrameArray exceptionStack;

    private OresGuestException(Throwable original) {
        this(snapshot(unwrap(original)));
    }

    private OresGuestException(Snapshot snapshot) {
        // Keep stack materialization enabled so PolyglotException can consume
        // the custom InteropLibrary exception stack below. Oreslang evaluator
        // and interop roots are internal; the exported elements themselves are
        // the bounded logical frames rather than carrier/JVM implementation frames.
        super(snapshot.displayMessage(), null, UNLIMITED_STACK_TRACE, null);
        this.errorName = snapshot.errorName();
        this.safeMessage = snapshot.message();
        this.guestStack = snapshot.guestStack();
        this.asyncTraces = snapshot.asyncTraces();
        this.elidedAsyncTraces = snapshot.elidedAsyncTraces();
        this.exceptionStack = !guestStack.isEmpty()
                ? guestStack
                : asyncTraces.isEmpty()
                        ? FrameArray.EMPTY
                        : asyncTraces.first().frames();
    }

    public static OresGuestException from(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof OresGuestException guest) return guest;
        return new OresGuestException(failure);
    }

    /**
     * Only failures carrying Oreslang logical evidence are projected as guest
     * exceptions. Internal runtime defects without guest evidence remain host
     * implementation errors instead of being silently reclassified.
     */
    public static boolean hasLogicalDiagnostics(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Throwable actual = unwrap(failure);
        if (actual instanceof OresGuestException) return true;
        for (Throwable suppressed : actual.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalGuestStackTrace
                    || suppressed instanceof OresAsyncTrace.LogicalAsyncStackTrace
                    || suppressed instanceof OresAsyncTrace.LogicalAsyncTraceElision) {
                return true;
            }
        }
        return false;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    ExceptionType getExceptionType() {
        return ExceptionType.RUNTIME_ERROR;
    }

    @ExportMessage
    boolean hasExceptionStackTrace() {
        return !exceptionStack.isEmpty();
    }

    @ExportMessage
    Object getExceptionStackTrace() throws UnsupportedMessageException {
        if (exceptionStack.isEmpty()) throw UnsupportedMessageException.create();
        return exceptionStack;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    Object getMembers(boolean includeInternal) {
        return MEMBERS;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isMemberReadable(String member) {
        return switch (member) {
            case "schema", "name", "message", "guest_stack",
                    "async_traces", "elided_async_traces" -> true;
            default -> false;
        };
    }

    @ExportMessage
    Object readMember(String member) throws UnknownIdentifierException {
        return switch (member) {
            case "schema" -> OresDiagnosticRenderer.SCHEMA;
            case "name" -> errorName;
            case "message" -> safeMessage;
            case "guest_stack" -> guestStack;
            case "async_traces" -> asyncTraces;
            case "elided_async_traces" -> elidedAsyncTraces;
            default -> throw UnknownIdentifierException.create(member);
        };
    }

    private static Snapshot snapshot(Throwable failure) {
        String errorName = safeText(failure.getClass().getSimpleName(), MAX_SYMBOL_CHARS);
        if (errorName.isBlank()) errorName = "Error";
        String message = safeText(failure.getMessage(), MAX_TEXT_CHARS);

        FrameArray guest = FrameArray.EMPTY;
        ArrayList<TraceValue> async = new ArrayList<>();
        long elidedAsync = 0;

        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalGuestStackTrace logical
                    && guest.isEmpty()) {
                guest = frames(logical.snapshot());
            } else if (suppressed instanceof OresAsyncTrace.LogicalAsyncStackTrace logical) {
                async.add(new TraceValue(
                        frames(logical.snapshot()),
                        logical.snapshot().elidedEvents()));
            } else if (suppressed instanceof OresAsyncTrace.LogicalAsyncTraceElision elision) {
                elidedAsync = saturatingAdd(elidedAsync, elision.elidedTraces());
            }
        }

        TraceArray asyncValues = new TraceArray(async);
        String display = message.isBlank() ? errorName : errorName + ": " + message;
        return new Snapshot(
                errorName,
                message,
                display,
                guest,
                asyncValues,
                elidedAsync);
    }

    private static FrameArray frames(OresAsyncTrace.TraceSnapshot trace) {
        ArrayList<FrameValue> frames = new ArrayList<>();
        frames.add(FrameValue.frame(
                "frame",
                trace.currentFrame().symbol(),
                trace.currentFrame().site()));

        List<OresAsyncTrace.Event> events = trace.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            OresAsyncTrace.Event event = events.get(i);
            if (event instanceof OresAsyncTrace.AwaitEvent awaited) {
                frames.add(FrameValue.frame("await", "<await>", awaited.site()));
            } else if (event instanceof OresAsyncTrace.CallEvent call) {
                frames.add(FrameValue.frame(
                        "call",
                        call.caller().symbol(),
                        call.site()));
            } else if (event instanceof OresAsyncTrace.TailEvent tail) {
                String symbol = "<tail-await "
                        + tail.from().symbol()
                        + " -> "
                        + tail.to().symbol()
                        + (tail.repetitions() == 1
                                ? ""
                                : " x" + tail.repetitions())
                        + ">";
                frames.add(FrameValue.frame("tail_await", symbol, tail.site()));
            } else if (event instanceof OresAsyncTrace.BoundaryEvent boundary) {
                String kind = boundary.kind()
                        .name()
                        .toLowerCase(Locale.ROOT);
                frames.add(FrameValue.frame(
                        kind,
                        "<" + kind.replace('_', '-') + ">",
                        boundary.site()));
            }
        }

        if (trace.elidedEvents() > 0) {
            frames.add(FrameValue.elided(trace.elidedEvents()));
        }
        return new FrameArray(frames);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                        || current instanceof ExecutionException)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
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

    private record Snapshot(
            String errorName,
            String message,
            String displayMessage,
            FrameArray guestStack,
            TraceArray asyncTraces,
            long elidedAsyncTraces) { }

    @ExportLibrary(InteropLibrary.class)
    static final class FrameValue implements TruffleObject {
        private static final MemberNames MEMBERS = new MemberNames(
                "kind",
                "symbol",
                "source",
                "line",
                "column",
                "generation",
                "elided_events");

        private final String kind;
        private final String symbol;
        private final String source;
        private final long line;
        private final long column;
        private final String generation;
        private final long elidedEvents;

        private FrameValue(
                String kind,
                String symbol,
                String source,
                long line,
                long column,
                String generation,
                long elidedEvents) {
            this.kind = safeText(kind, MAX_SYMBOL_CHARS);
            this.symbol = safeText(symbol, MAX_SYMBOL_CHARS);
            this.source = safeText(source, MAX_TEXT_CHARS);
            this.line = line;
            this.column = column;
            this.generation = safeText(generation, MAX_SYMBOL_CHARS);
            this.elidedEvents = elidedEvents;
        }

        private static FrameValue frame(
                String kind,
                String symbol,
                OresAsyncTrace.SourceSite site) {
            return new FrameValue(
                    kind,
                    symbol,
                    site.sourceId(),
                    site.line(),
                    site.column(),
                    site.codeGenerationId(),
                    0);
        }

        private static FrameValue elided(long count) {
            return new FrameValue(
                    "elided",
                    "<elided>",
                    "<unknown>",
                    -1,
                    -1,
                    "",
                    count);
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasExecutableName() {
            return true;
        }

        @ExportMessage
        Object getExecutableName() {
            return symbol;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasMembers() {
            return true;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        Object getMembers(boolean includeInternal) {
            return MEMBERS;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean isMemberReadable(String member) {
            return switch (member) {
                case "kind", "symbol", "source", "line", "column",
                        "generation", "elided_events" -> true;
                default -> false;
            };
        }

        @ExportMessage
        Object readMember(String member) throws UnknownIdentifierException {
            return switch (member) {
                case "kind" -> kind;
                case "symbol" -> symbol;
                case "source" -> source;
                case "line" -> line;
                case "column" -> column;
                case "generation" -> generation;
                case "elided_events" -> elidedEvents;
                default -> throw UnknownIdentifierException.create(member);
            };
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class TraceValue implements TruffleObject {
        private static final MemberNames MEMBERS =
                new MemberNames("frames", "elided_events");

        private final FrameArray frames;
        private final long elidedEvents;

        private TraceValue(FrameArray frames, long elidedEvents) {
            this.frames = Objects.requireNonNull(frames, "frames");
            this.elidedEvents = elidedEvents;
        }

        private FrameArray frames() {
            return frames;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasMembers() {
            return true;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        Object getMembers(boolean includeInternal) {
            return MEMBERS;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean isMemberReadable(String member) {
            return member.equals("frames") || member.equals("elided_events");
        }

        @ExportMessage
        Object readMember(String member) throws UnknownIdentifierException {
            return switch (member) {
                case "frames" -> frames;
                case "elided_events" -> elidedEvents;
                default -> throw UnknownIdentifierException.create(member);
            };
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class FrameArray implements TruffleObject {
        private static final FrameArray EMPTY = new FrameArray(List.of());
        private final List<FrameValue> values;

        private FrameArray(List<FrameValue> values) {
            this.values = List.copyOf(values);
        }

        private boolean isEmpty() {
            return values.isEmpty();
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return values.size();
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < values.size();
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) {
                throw InvalidArrayIndexException.create(index);
            }
            return values.get((int) index);
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class TraceArray implements TruffleObject {
        private final List<TraceValue> values;

        private TraceArray(List<TraceValue> values) {
            this.values = List.copyOf(values);
        }

        private boolean isEmpty() {
            return values.isEmpty();
        }

        private TraceValue first() {
            return values.getFirst();
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return values.size();
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < values.size();
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) {
                throw InvalidArrayIndexException.create(index);
            }
            return values.get((int) index);
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class MemberNames implements TruffleObject {
        private final String[] names;

        private MemberNames(String... names) {
            this.names = names.clone();
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return names.length;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < names.length;
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) {
                throw InvalidArrayIndexException.create(index);
            }
            return names[(int) index];
        }
    }
}
