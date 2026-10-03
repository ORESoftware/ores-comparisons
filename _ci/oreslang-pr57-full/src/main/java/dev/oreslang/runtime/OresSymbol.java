package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Canonical process-runtime symbol used for protocol tags and other immutable identities.
 *
 * Trusted Oreslang execution shares one bounded interner for the lifetime of this runtime
 * heap. The registry holds strong references deliberately: ordinary GC never reclaims a
 * symbol or changes its identity. Adversarial/untrusted isolates are not participants in
 * this registry and must not receive symbol-bearing values.
 */
public final class OresSymbol {
    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_INTERNED_SYMBOLS = 65_536;

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ConcurrentHashMap<String, OresSymbol> INTERNED = new ConcurrentHashMap<>();
    private static final Object INTERN_LOCK = new Object();

    private final String name;
    private final int hash;

    private OresSymbol(String name) {
        this.name = validateName(name);
        this.hash = name.hashCode();
    }

    /**
     * Trusted-host/runtime interning entry point.
     *
     * Equal names always return the exact same OresSymbol instance while this process
     * runtime heap is alive. Creation is bounded so trusted dynamic input cannot grow an
     * Erlang-style atom table without limit.
     */
    public static OresSymbol of(String name) {
        String validated = validateName(name);
        OresSymbol existing = INTERNED.get(validated);
        if (existing != null) return existing;

        synchronized (INTERN_LOCK) {
            existing = INTERNED.get(validated);
            if (existing != null) return existing;
            if (INTERNED.size() >= MAX_INTERNED_SYMBOLS) {
                throw new IllegalStateException(
                        "process symbol limit exceeded: " + MAX_INTERNED_SYMBOLS);
            }
            OresSymbol created = new OresSymbol(validated);
            INTERNED.put(validated, created);
            return created;
        }
    }

    /**
     * Guest-visible interning entry point. Untrusted/adversarial isolates are deliberately
     * excluded from the process symbol registry.
     */
    public static OresSymbol of(String name, IsolatePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.adversarial()) {
            throw new SecurityException(
                    "process-wide symbols are unavailable to adversarial/untrusted isolates");
        }
        return of(name);
    }

    public static int internedCount() {
        return INTERNED.size();
    }

    public String name() {
        return name;
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "symbol name");
        if (name.isBlank()) throw new IllegalArgumentException("symbol name cannot be blank");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "symbol name exceeds " + MAX_NAME_LENGTH + " characters");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "symbol name must be an identifier ([A-Za-z_][A-Za-z0-9_]*)");
        }
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return ":" + name;
    }
}
