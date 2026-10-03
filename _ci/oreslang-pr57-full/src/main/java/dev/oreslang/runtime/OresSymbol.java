package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dev.oreslang.symbol.SymbolNames;

/**
 * Canonical Oreslang symbol used for protocol tags and other immutable identities.
 *
 * <p>Within one trusted runtime heap, equal names intern to the exact same
 * {@code OresSymbol} instance. Across separate trusted Graal/native isolate
 * heaps, Java object identity is impossible by definition, so Oreslang symbol
 * identity is the deterministic {@link SymbolId} plus the canonical name. The
 * stable ID lets a supervisor reconstruct and verify the same language-level
 * symbol on the other side of an isolate boundary without sharing pointers.</p>
 *
 * <p>The local interner is deliberately strongly rooted and bounded. Ordinary
 * GC never reclaims symbols or changes their identity. Adversarial/untrusted
 * isolates are not participants and must not create or receive symbols.</p>
 */
public final class OresSymbol {
    public static final int MAX_NAME_LENGTH = SymbolNames.MAX_NAME_LENGTH;
    public static final int MAX_INTERNED_SYMBOLS = 65_536;
    private static final byte[] ID_DOMAIN =
            "oreslang-symbol-v1\0".getBytes(StandardCharsets.UTF_8);

    private static final ConcurrentHashMap<String, OresSymbol> INTERNED =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SymbolId, OresSymbol> INTERNED_BY_ID =
            new ConcurrentHashMap<>();
    private static final Object INTERN_LOCK = new Object();

    /**
     * Stable 256-bit symbol identity.
     *
     * <p>The digest is deterministic across trusted isolate heaps and process
     * restarts. Equality still verifies the canonical name as well, so even a
     * hypothetical digest collision cannot silently alias two symbols.</p>
     */
    public record SymbolId(long a, long b, long c, long d) {
        public String hex() {
            ByteBuffer bytes = ByteBuffer.allocate(32);
            bytes.putLong(a).putLong(b).putLong(c).putLong(d);
            return HexFormat.of().formatHex(bytes.array());
        }

        @Override
        public String toString() {
            return hex();
        }
    }

    /** Immutable representation suitable for a trusted isolate bridge. */
    public record CanonicalForm(SymbolId id, String name) {
        public CanonicalForm {
            Objects.requireNonNull(id, "symbol id");
            name = validateName(name);
        }
    }

    private final String name;
    private final SymbolId id;
    private final int hash;

    private OresSymbol(String name, SymbolId id) {
        this.name = validateName(name);
        this.id = Objects.requireNonNull(id, "id");
        this.hash = 31 * name.hashCode() + id.hashCode();
    }

    /**
     * Trusted-host/runtime interning entry point.
     *
     * <p>Equal names return the exact same object inside this runtime heap.
     * Creation is bounded so trusted dynamic input cannot grow an Erlang-style
     * atom table without limit.</p>
     */
    public static OresSymbol of(String name) {
        String validated = validateName(name);
        OresSymbol existing = INTERNED.get(validated);
        if (existing != null) return existing;

        SymbolId id = idForValidatedName(validated);
        synchronized (INTERN_LOCK) {
            existing = INTERNED.get(validated);
            if (existing != null) return existing;
            if (INTERNED.size() >= MAX_INTERNED_SYMBOLS) {
                throw new IllegalStateException(
                        "runtime symbol limit exceeded: " + MAX_INTERNED_SYMBOLS);
            }

            OresSymbol idExisting = INTERNED_BY_ID.get(id);
            if (idExisting != null && !idExisting.name.equals(validated)) {
                throw new IllegalStateException(
                        "cryptographic SymbolId collision between :"
                                + idExisting.name + " and :" + validated);
            }

            OresSymbol created = new OresSymbol(validated, id);
            INTERNED.put(validated, created);
            INTERNED_BY_ID.put(id, created);
            return created;
        }
    }

    /**
     * Guest-visible interning entry point. Untrusted/adversarial isolates are
     * deliberately excluded from the trusted symbol namespace.
     */
    public static OresSymbol of(String name, IsolatePolicy policy) {
        requireTrustedPolicy(policy);
        return of(name);
    }

    /**
     * Reconstruct a symbol received through a trusted isolate bridge.
     *
     * <p>The claimed ID is recomputed from the canonical name before the local
     * heap interner is touched. This prevents a corrupted/forged bridge payload
     * from aliasing one symbolic name to another.</p>
     */
    public static OresSymbol fromCanonical(
            CanonicalForm canonical,
            IsolatePolicy policy) {
        requireTrustedPolicy(policy);
        Objects.requireNonNull(canonical, "canonical");
        SymbolId expected = idForName(canonical.name());
        if (!expected.equals(canonical.id())) {
            throw new IllegalArgumentException(
                    "symbol canonical form has mismatched id for :" + canonical.name());
        }
        return of(canonical.name());
    }

    /** Deterministic identity without interning or consuming registry capacity. */
    public static SymbolId idForName(String name) {
        return idForValidatedName(validateName(name));
    }

    public static int internedCount() {
        return INTERNED.size();
    }

    public String name() {
        return name;
    }

    public SymbolId id() {
        return id;
    }

    public CanonicalForm canonicalForm() {
        return new CanonicalForm(id, name);
    }

    /** Pattern/runtime helper that validates both canonical name and stable ID. */
    public boolean matchesName(String candidate) {
        return name.equals(validateName(candidate));
    }

    private static void requireTrustedPolicy(IsolatePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.adversarial()) {
            throw new SecurityException(
                    "trusted symbols are unavailable to adversarial/untrusted isolates");
        }
    }

    private static SymbolId idForValidatedName(String validated) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ID_DOMAIN);
            byte[] bytes = digest.digest(validated.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new SymbolId(
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong(),
                    buffer.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static String validateName(String name) {
        return SymbolNames.validate(name);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OresSymbol symbol)) return false;
        return name.equals(symbol.name) && id.equals(symbol.id);
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
