package dev.oreslang.symbol;

import java.util.Objects;
import java.util.regex.Pattern;

/** Shared source/runtime validation contract for Oreslang Symbol names. */
public final class SymbolNames {
    public static final int MAX_NAME_LENGTH = 128;
    private static final Pattern VALID_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SymbolNames() { }

    public static String validate(String name) {
        Objects.requireNonNull(name, "symbol name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("symbol name cannot be blank");
        }
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
}
