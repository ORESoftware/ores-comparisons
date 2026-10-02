package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime matcher shared by receive lowering and the reference interpreter.
 *
 * Matching is pure and never scans a mailbox. Actor receive always dequeues one
 * FIFO message first, then applies a pattern to that one value.
 */
public final class PatternSupport {
    private PatternSupport() { }

    public static Optional<Map<String, Object>> match(Ast.Pattern pattern, Object value) {
        LinkedHashMap<String, Object> captures = new LinkedHashMap<>();
        if (!matchInto(pattern, value, captures)) return Optional.empty();
        return Optional.of(Collections.unmodifiableMap(new LinkedHashMap<>(captures)));
    }

    private static boolean matchInto(
            Ast.Pattern pattern,
            Object value,
            LinkedHashMap<String, Object> captures) {
        if (pattern instanceof Ast.WildcardPattern) return true;

        if (pattern instanceof Ast.BindingPattern binding) {
            return capture(binding.name(), value, captures);
        }

        if (pattern instanceof Ast.TypedBindingPattern binding) {
            if (!runtimeTypeMatches(binding.type(), value)) return false;
            return capture(binding.name(), value, captures);
        }

        if (pattern instanceof Ast.LiteralPattern literal) {
            return valueEquals(runtimeLiteral(literal.value()), value);
        }

        List<Ast.Pattern> elements;
        if (pattern instanceof Ast.TuplePattern tuple) elements = tuple.elements();
        else if (pattern instanceof Ast.ListPattern list) elements = list.elements();
        else throw new IllegalArgumentException("unsupported pattern " + pattern.getClass().getSimpleName());

        List<?> values = sequence(value);
        if (values == null || values.size() != elements.size()) return false;

        LinkedHashMap<String, Object> staged = new LinkedHashMap<>(captures);
        for (int i = 0; i < elements.size(); i++) {
            if (!matchInto(elements.get(i), values.get(i), staged)) return false;
        }
        captures.clear();
        captures.putAll(staged);
        return true;
    }

    private static boolean capture(String name, Object value, LinkedHashMap<String, Object> captures) {
        if (!captures.containsKey(name)) {
            captures.put(name, value);
            return true;
        }
        return valueEquals(captures.get(name), value);
    }

    private static Object runtimeLiteral(Object value) {
        if (value instanceof Ast.Symbol symbol) return OresSymbol.of(symbol.name());
        return value;
    }

    private static List<?> sequence(Object value) {
        if (value instanceof List<?> list) return list;
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) result.add(Array.get(value, i));
            return result;
        }
        return null;
    }

    private static boolean valueEquals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            if (isIntegral(a) && isIntegral(b)) return a.longValue() == b.longValue();
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        return Objects.equals(left, right);
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long;
    }

    private static boolean runtimeTypeMatches(Ast.TypeRef type, Object value) {
        if (type == null || type.name().equals("$infer$")) return true;
        if (value == null) return false;
        return switch (type.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                    "int", "uint", "bigint" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger;
            case "f32", "f64", "float", "decimal" -> value instanceof Number;
            case "bool", "Bool" -> value instanceof Boolean;
            case "string", "String" -> value instanceof String;
            case "Symbol" -> value instanceof OresSymbol;
            case "Array", "List" -> value instanceof List<?> || value.getClass().isArray();
            default -> true; // nominal guest types are checked by generated protocol/type guards.
        };
    }
}
