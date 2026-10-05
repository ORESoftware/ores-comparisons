package dev.oreslang.runtime.math;

import dev.oreslang.runtime.ImmutableGuestValue;

import java.util.ArrayList;
import java.util.List;

/** Immutable column vector backed by the same EJML kernels as OresMatrix. */
public final class OresVector implements ImmutableGuestValue {
    private final OresMatrix value;

    private OresVector(OresMatrix value) {
        if (value.cols() != 1) throw new IllegalArgumentException("vector backing matrix must have one column");
        this.value = value;
    }

    public static OresVector fromValues(List<?> values) {
        return new OresVector(OresMatrix.fromColumnValues(values));
    }

    static OresVector fromMatrix(OresMatrix value) { return new OresVector(value); }
    OresMatrix asMatrix() { return value; }

    public int length() { return value.rows(); }
    public boolean isComplex() { return value.isComplex(); }
    public long logicalBytes() { return Math.addExact(24L, value.logicalBytes()); }
    public Object get(int index) { return value.get(index, 0); }

    public OresVector add(OresVector other) {
        requireLength(other);
        return new OresVector(value.add(other.value));
    }

    public OresVector subtract(OresVector other) {
        requireLength(other);
        return new OresVector(value.subtract(other.value));
    }

    public OresVector scale(Object scalar) { return new OresVector(value.scale(scalar)); }

    /** Hermitian inner product: conjugates the left vector for complex data. */
    public OresComplex dot(OresVector other) {
        requireLength(other);
        OresComplex sum = OresComplex.ZERO;
        for (int i = 0; i < length(); i++) {
            OresComplex left = OresComplex.of(get(i)).conjugate();
            OresComplex right = OresComplex.of(other.get(i));
            sum = sum.add(left.multiply(right));
        }
        return sum;
    }

    public double norm() { return value.norm(); }

    public OresMatrix outer(OresVector other) {
        List<List<?>> rows = new ArrayList<>(length());
        if (!isComplex() && !other.isComplex()) {
            for (int i = 0; i < length(); i++) {
                List<Object> row = new ArrayList<>(other.length());
                double left = ((Number) get(i)).doubleValue();
                for (int j = 0; j < other.length(); j++) {
                    row.add(left * ((Number) other.get(j)).doubleValue());
                }
                rows.add(row);
            }
            return OresMatrix.fromRows(rows);
        }

        for (int i = 0; i < length(); i++) {
            List<Object> row = new ArrayList<>(other.length());
            OresComplex left = OresComplex.of(get(i));
            for (int j = 0; j < other.length(); j++) {
                row.add(left.multiply(OresComplex.of(other.get(j)).conjugate()));
            }
            rows.add(row);
        }
        return OresMatrix.fromRows(rows);
    }

    @Override public Iterable<?> sharedStateChildren() { return List.of(); }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("vector[");
        for (int i = 0; i < length(); i++) {
            if (i > 0) out.append(", ");
            out.append(get(i));
        }
        return out.append(']').toString();
    }

    private void requireLength(OresVector other) {
        if (length() != other.length()) throw new IllegalArgumentException("vector lengths must match");
    }
}
