package dev.oreslang.runtime;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Small immutable symbol value used for protocol tags and pattern matching.
 *
 * Unlike Erlang atoms, Oreslang symbols are ordinary garbage-collected values:
 * there is deliberately no process-wide/global intern table that untrusted
 * input can grow without bound.
 */
public record OresSymbol(String name) {
    public static final int MAX_NAME_LENGTH = 128;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public OresSymbol {
        Objects.requireNonNull(name, "symbol name");
        if (name.isBlank()) throw new IllegalArgumentException("symbol name cannot be blank");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("symbol name exceeds " + MAX_NAME_LENGTH + " characters");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "symbol name must be an identifier ([A-Za-z_][A-Za-z0-9_]*)");
        }
    }

    public static OresSymbol of(String name) {
        return new OresSymbol(name);
    }

    @Override
    public String toString() {
        return ":" + name;
    }
}
