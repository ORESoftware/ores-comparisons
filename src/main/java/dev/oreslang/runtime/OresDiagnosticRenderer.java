package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.graalvm.polyglot.PolyglotException;

/**
 * Canonical host-facing Oreslang error renderer.
 *
 * <p>Diagnostics intentionally render guest causal metadata instead of physical
 * carrier-thread stacks. Text and JSON are derived from the same bounded data
 * model so CI, humans, JIT, and Native Image observe the same failure shape.</p>
 */
public final class OresDiagnosticRenderer {
    public static final String SCHEMA = "oreslang.error.v1";
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final int MAX_MESSAGE_CHARS = 4096;
    private static final int MAX_SOURCE_CHARS = 1024;
    private static final int MAX_TYPE_CHARS = 256;
    private static final int MAX_SUPPRESSED = 16;
    private static final int MAX_ERROR_NODES = 64;
    private static final int MAX_RENDERED_ASYNC_TRACES_PER_FAILURE = 8;
    private static final int MAX_RENDERED_ASYNC_TRACES_TOTAL = 16;
    private static final int MAX_RENDERED_GUEST_STACKS_TOTAL = 16;
    private static final int MAX_RENDERED_TRACE_EVENTS = 64;

    private OresDiagnosticRenderer() { }

    public static String renderText(Throwable failure) {
        if (failure == null) throw new NullPointerException("failure");
        failure = diagnosticRoot(failure);
        StringBuilder out = new StringBuilder();
        appendTextFailure(out, failure, new RenderState(), 0, false);
        return out.toString();
    }

    public static String renderJson(Throwable failure) {
        if (failure == null) throw new NullPointerException("failure");
        failure = diagnosticRoot(failure);
        StringBuilder out = new StringBuilder(512);
        out.append("{\"schema\":");
        appendJsonString(out, SCHEMA);
        out.append(",\"error\":");
        appendJsonFailure(out, failure, new RenderState(), 0);
        out.append('}');
        return out.toString();
    }

    private static void appendTextFailure(
            StringBuilder out,
            Throwable failure,
            RenderState state,
            int depth,
            boolean causedBy) {
        if (depth >= MAX_CAUSE_DEPTH) {
            if (causedBy) out.append("\nCaused by: ");
            out.append("<cause chain truncated>");
            return;
        }
        if (state.active.containsKey(failure)) {
            if (causedBy) out.append("\nCaused by: ");
            out.append("<cause cycle elided>");
            return;
        }
        if (!state.consumeNode()) {
            if (causedBy) out.append("\nCaused by: ");
            out.append("<error graph truncated>");
            return;
        }

        state.active.put(failure, Boolean.TRUE);
        try {
        if (causedBy) out.append("\nCaused by: ");
        out.append(safeHuman(typeName(failure)));
        String message = bounded(failure.getMessage(), MAX_MESSAGE_CHARS);
        if (!message.isBlank()) out.append(": ").append(safeHuman(message));

        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalGuestStackTrace guest) {
                if (state.consumeGuestStack()) {
                    out.append("\nOreslang logical stack:");
                    appendTextTrace(out, guest.snapshot());
                } else {
                    out.append("\n    ... Oreslang logical stack elided");
                }
                break;
            }
        }

        int renderedLogical = 0;
        long elidedTraces = 0;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalAsyncStackTrace logical) {
                if (renderedLogical >= MAX_RENDERED_ASYNC_TRACES_PER_FAILURE
                        || !state.consumeAsyncTrace()) {
                    elidedTraces = saturatingAdd(elidedTraces, 1);
                    continue;
                }
                renderedLogical++;
                out.append("\nOreslang causal async stack:");
                appendTextTrace(out, logical.snapshot());
            } else if (suppressed instanceof OresAsyncTrace.LogicalAsyncTraceElision elision) {
                elidedTraces = saturatingAdd(elidedTraces, elision.elidedTraces());
            }
        }
        if (elidedTraces > 0) {
            out.append("\n    ... ").append(elidedTraces)
                    .append(" additional Oreslang causal trace(s) elided");
        }

        int renderedSuppressed = 0;
        int elidedSuppressed = 0;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalGuestStackTrace
                    || suppressed instanceof OresAsyncTrace.LogicalAsyncStackTrace
                    || suppressed instanceof OresAsyncTrace.LogicalAsyncTraceElision) {
                continue;
            }
            if (renderedSuppressed >= MAX_SUPPRESSED) {
                elidedSuppressed++;
                continue;
            }
            out.append("\nSuppressed: ");
            appendTextFailure(out, suppressed, state, depth + 1, false);
            renderedSuppressed++;
        }
        if (elidedSuppressed > 0) {
            out.append("\n    ... ").append(elidedSuppressed)
                    .append(" additional suppressed error(s) elided");
        }

        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) {
            appendTextFailure(out, cause, state, depth + 1, true);
        }
        } finally {
            state.active.remove(failure);
        }
    }

    private static void appendTextTrace(StringBuilder out, OresAsyncTrace.TraceSnapshot trace) {
        appendTextFrame(out, "at", trace.currentFrame());
        var events = trace.events();
        int firstRendered = Math.max(0, events.size() - MAX_RENDERED_TRACE_EVENTS);
        for (int i = events.size() - 1; i >= firstRendered; i--) {
            OresAsyncTrace.Event event = events.get(i);
            if (event instanceof OresAsyncTrace.AwaitEvent awaited) {
                appendTextBoundary(out, "await", awaited.site(), null);
            } else if (event instanceof OresAsyncTrace.CallEvent call) {
                appendTextFrame(
                        out,
                        "at",
                        new OresAsyncTrace.Frame(call.caller().symbol(), call.site()));
            } else if (event instanceof OresAsyncTrace.TailEvent tail) {
                String detail = tail.from().symbol() + " -> " + tail.to().symbol()
                        + (tail.repetitions() == 1
                                ? ""
                                : " repeated " + tail.repetitions() + " times");
                appendTextBoundary(out, "tail-await", tail.site(), detail);
            } else if (event instanceof OresAsyncTrace.BoundaryEvent boundary) {
                appendTextBoundary(
                        out,
                        boundary.kind().name().toLowerCase(java.util.Locale.ROOT)
                                .replace('_', '-'),
                        boundary.site(),
                        boundary.frame().symbol());
            }
        }
        long elidedEvents = saturatingAdd(trace.elidedEvents(), firstRendered);
        if (elidedEvents > 0) {
            out.append("\n    ... ").append(elidedEvents)
                    .append(" older async trace events elided");
        }
    }

    private static void appendTextFrame(
            StringBuilder out,
            String prefix,
            OresAsyncTrace.Frame frame) {
        out.append("\n    ").append(prefix).append(' ')
                .append(safeHuman(bounded(frame.symbol(), MAX_TYPE_CHARS)));
        appendTextSite(out, frame.site());
    }

    private static void appendTextBoundary(
            StringBuilder out,
            String kind,
            OresAsyncTrace.SourceSite site,
            String detail) {
        out.append("\n    --- ").append(safeHuman(kind));
        if (detail != null && !detail.isBlank()) {
            out.append(' ').append(safeHuman(bounded(detail, MAX_MESSAGE_CHARS)));
        }
        appendTextSite(out, site);
        out.append(" ---");
    }

    private static void appendTextSite(
            StringBuilder out,
            OresAsyncTrace.SourceSite site) {
        out.append(" (").append(safeHuman(bounded(site.sourceId(), MAX_SOURCE_CHARS)));
        if (site.line() > 0) {
            out.append(':').append(site.line());
            if (site.column() > 0) out.append(':').append(site.column());
        }
        if (site.codeGenerationId() != null && !site.codeGenerationId().isBlank()) {
            out.append(" @gen=")
                    .append(safeHuman(bounded(site.codeGenerationId(), MAX_TYPE_CHARS)));
        }
        out.append(')');
    }

    private static void appendJsonFailure(
            StringBuilder out,
            Throwable failure,
            RenderState state,
            int depth) {
        if (depth >= MAX_CAUSE_DEPTH) {
            out.append("{\"truncated\":true,\"reason\":\"cause_depth\"}");
            return;
        }
        if (state.active.containsKey(failure)) {
            out.append("{\"truncated\":true,\"reason\":\"cause_cycle\"}");
            return;
        }
        if (!state.consumeNode()) {
            out.append("{\"truncated\":true,\"reason\":\"error_budget\"}");
            return;
        }

        state.active.put(failure, Boolean.TRUE);
        try {
        out.append('{');
        out.append("\"type\":");
        appendJsonString(out, bounded(typeName(failure), MAX_TYPE_CHARS));
        out.append(",\"name\":");
        appendJsonString(out, bounded(failure.getClass().getSimpleName(), MAX_TYPE_CHARS));
        out.append(",\"message\":");
        appendJsonString(out, bounded(failure.getMessage(), MAX_MESSAGE_CHARS));

        OresAsyncTrace.LogicalGuestStackTrace guestStack = null;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalGuestStackTrace guest) {
                guestStack = guest;
                break;
            }
        }
        out.append(",\"guest_stack\":");
        boolean guestStackElided = false;
        if (guestStack == null) {
            out.append("null");
        } else if (state.consumeGuestStack()) {
            appendJsonTrace(out, guestStack.snapshot());
        } else {
            guestStackElided = true;
            out.append("null");
        }
        out.append(",\"guest_stack_elided\":").append(guestStackElided);

        out.append(",\"async_traces\":[");
        boolean firstTrace = true;
        int renderedLogical = 0;
        long elidedTraces = 0;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalAsyncStackTrace logical) {
                if (renderedLogical >= MAX_RENDERED_ASYNC_TRACES_PER_FAILURE
                        || !state.consumeAsyncTrace()) {
                    elidedTraces = saturatingAdd(elidedTraces, 1);
                    continue;
                }
                renderedLogical++;
                if (!firstTrace) out.append(',');
                firstTrace = false;
                appendJsonTrace(out, logical.snapshot());
            } else if (suppressed instanceof OresAsyncTrace.LogicalAsyncTraceElision elision) {
                elidedTraces = saturatingAdd(elidedTraces, elision.elidedTraces());
            }
        }
        out.append(']');
        out.append(",\"elided_async_traces\":").append(elidedTraces);

        out.append(",\"suppressed\":[");
        int renderedSuppressed = 0;
        int elidedSuppressed = 0;
        boolean firstSuppressed = true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof OresAsyncTrace.LogicalGuestStackTrace
                    || suppressed instanceof OresAsyncTrace.LogicalAsyncStackTrace
                    || suppressed instanceof OresAsyncTrace.LogicalAsyncTraceElision) {
                continue;
            }
            if (renderedSuppressed >= MAX_SUPPRESSED) {
                elidedSuppressed++;
                continue;
            }
            if (!firstSuppressed) out.append(',');
            firstSuppressed = false;
            appendJsonFailure(out, suppressed, state, depth + 1);
            renderedSuppressed++;
        }
        out.append(']');
        out.append(",\"elided_suppressed\":").append(elidedSuppressed);

        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) {
            out.append(",\"cause\":");
            appendJsonFailure(out, cause, state, depth + 1);
        } else {
            out.append(",\"cause\":null");
        }
        out.append('}');
        } finally {
            state.active.remove(failure);
        }
    }

    private static void appendJsonTrace(StringBuilder out, OresAsyncTrace.TraceSnapshot trace) {
        out.append('{');
        out.append("\"current\":");
        appendJsonFrame(out, trace.currentFrame());
        out.append(",\"event_order\":\"oldest_to_newest\"");
        out.append(",\"events\":[");
        boolean first = true;
        var events = trace.events();
        int firstRendered = Math.max(0, events.size() - MAX_RENDERED_TRACE_EVENTS);
        for (int i = firstRendered; i < events.size(); i++) {
            OresAsyncTrace.Event event = events.get(i);
            if (!first) out.append(',');
            first = false;
            appendJsonEvent(out, event);
        }
        out.append(']');
        out.append(",\"elided_events\":")
                .append(saturatingAdd(trace.elidedEvents(), firstRendered));
        out.append('}');
    }

    private static void appendJsonEvent(StringBuilder out, OresAsyncTrace.Event event) {
        out.append('{');
        if (event instanceof OresAsyncTrace.CallEvent call) {
            out.append("\"kind\":\"call\",\"caller\":");
            appendJsonFrame(out, call.caller());
            out.append(",\"callee\":");
            appendJsonFrame(out, call.callee());
            out.append(",\"site\":");
            appendJsonSite(out, call.site());
        } else if (event instanceof OresAsyncTrace.AwaitEvent awaited) {
            out.append("\"kind\":\"await\",\"frame\":");
            appendJsonFrame(out, awaited.frame());
            out.append(",\"site\":");
            appendJsonSite(out, awaited.site());
        } else if (event instanceof OresAsyncTrace.TailEvent tail) {
            out.append("\"kind\":\"tail_await\",\"from\":");
            appendJsonFrame(out, tail.from());
            out.append(",\"to\":");
            appendJsonFrame(out, tail.to());
            out.append(",\"site\":");
            appendJsonSite(out, tail.site());
            out.append(",\"repetitions\":").append(tail.repetitions());
        } else if (event instanceof OresAsyncTrace.BoundaryEvent boundary) {
            out.append("\"kind\":");
            appendJsonString(out, boundary.kind().name().toLowerCase(java.util.Locale.ROOT));
            out.append(",\"frame\":");
            appendJsonFrame(out, boundary.frame());
            out.append(",\"site\":");
            appendJsonSite(out, boundary.site());
        } else {
            out.append("\"kind\":\"unknown\",\"site\":");
            appendJsonSite(out, event.site());
        }
        out.append('}');
    }

    private static void appendJsonFrame(StringBuilder out, OresAsyncTrace.Frame frame) {
        out.append("{\"symbol\":");
        appendJsonString(out, bounded(frame.symbol(), MAX_TYPE_CHARS));
        out.append(",\"site\":");
        appendJsonSite(out, frame.site());
        out.append('}');
    }

    private static void appendJsonSite(StringBuilder out, OresAsyncTrace.SourceSite site) {
        out.append("{\"source\":");
        appendJsonString(out, bounded(site.sourceId(), MAX_SOURCE_CHARS));
        out.append(",\"line\":").append(site.line());
        out.append(",\"column\":").append(site.column());
        out.append(",\"generation\":");
        if (site.codeGenerationId() == null || site.codeGenerationId().isBlank()) {
            out.append("null");
        } else {
            appendJsonString(out, bounded(site.codeGenerationId(), MAX_TYPE_CHARS));
        }
        out.append('}');
    }

    private static Throwable diagnosticRoot(Throwable failure) {
        Throwable current = failure;
        for (int i = 0; i < 16; i++) {
            Throwable next = null;
            if ((current instanceof CompletionException || current instanceof ExecutionException)
                    && current.getCause() != null) {
                next = current.getCause();
            } else if (current instanceof PolyglotException polyglot
                    && polyglot.isHostException()) {
                Throwable host = polyglot.asHostException();
                if (host != current) next = host;
            }
            if (next == null || next == current) break;
            current = next;
        }
        return current;
    }

    private static String typeName(Throwable failure) {
        String canonical = failure.getClass().getCanonicalName();
        return canonical == null ? failure.getClass().getName() : canonical;
    }

    private static String bounded(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "…";
    }

    private static String safeHuman(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n') out.append("\\n");
            else if (ch == '\r') out.append("\\r");
            else if (ch == '\t') out.append("\\t");
            else if (Character.isISOControl(ch) || isBidiControl(ch)
                    || Character.isSurrogate(ch) || isLineSeparator(ch)) {
                appendUnicodeEscape(out, ch);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static void appendJsonString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20 || isBidiControl(ch) || Character.isSurrogate(ch)
                            || isLineSeparator(ch)) {
                        appendUnicodeEscape(out, ch);
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }

    private static boolean isLineSeparator(char ch) {
        return ch == '\u2028' || ch == '\u2029';
    }

    private static final class RenderState {
        private final IdentityHashMap<Throwable, Boolean> active = new IdentityHashMap<>();
        private int remaining = MAX_ERROR_NODES;
        private int remainingAsyncTraces = MAX_RENDERED_ASYNC_TRACES_TOTAL;
        private int remainingGuestStacks = MAX_RENDERED_GUEST_STACKS_TOTAL;

        private boolean consumeNode() {
            if (remaining <= 0) return false;
            remaining--;
            return true;
        }

        private boolean consumeAsyncTrace() {
            if (remainingAsyncTraces <= 0) return false;
            remainingAsyncTraces--;
            return true;
        }

        private boolean consumeGuestStack() {
            if (remainingGuestStacks <= 0) return false;
            remainingGuestStacks--;
            return true;
        }
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

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }
}
