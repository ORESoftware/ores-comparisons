package dev.oreslang.runtime.math;

import dev.oreslang.runtime.ImmutableGuestValue;
import org.ejml.data.Complex_F64;
import org.ejml.data.MatrixType;
import org.ejml.simple.SimpleMatrix;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable dense matrix value. EJML owns the primitive contiguous storage and
 * optimized kernels; Oreslang owns typing, actor-safety and language semantics.
 */
public final class OresMatrix implements ImmutableGuestValue {
    private final SimpleMatrix value;
    private final boolean complex;

    private OresMatrix(SimpleMatrix value, boolean complex, boolean copy) {
        this.value = copy ? value.copy() : value;
        this.complex = complex;
    }

    public static OresMatrix fromRows(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("matrix literal requires at least one row");
        }
        int columns = -1;
        boolean complex = false;
        List<List<?>> normalized = new ArrayList<>(rows.size());
        for (Object rawRow : rows) {
            if (!(rawRow instanceof List<?> row)) {
                throw new IllegalArgumentException("matrix rows must be arrays/lists");
            }
            if (columns < 0) columns = row.size();
            if (row.size() != columns) throw new IllegalArgumentException("matrix literal must be rectangular");
            if (columns == 0) throw new IllegalArgumentException("matrix rows cannot be empty");
            for (Object item : row) {
                requireScalar(item);
                if (item instanceof OresComplex) complex = true;
            }
            normalized.add(row);
        }

        SimpleMatrix matrix = complex
                ? new SimpleMatrix(rows.size(), columns, MatrixType.ZDRM)
                : new SimpleMatrix(rows.size(), columns);
        for (int row = 0; row < normalized.size(); row++) {
            for (int column = 0; column < columns; column++) {
                Object item = normalized.get(row).get(column);
                if (complex) {
                    OresComplex number = OresComplex.of(item);
                    matrix.set(row, column, number.real(), number.imaginary());
                } else {
                    matrix.set(row, column, ((Number) item).doubleValue());
                }
            }
        }
        return new OresMatrix(matrix, complex, false);
    }

    static OresMatrix fromColumnValues(List<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("vector literal requires at least one element");
        }
        List<List<?>> rows = new ArrayList<>(values.size());
        for (Object value : values) rows.add(List.of(value));
        return fromRows(rows);
    }

    public static OresMatrix identity(int size) {
        if (size <= 0) throw new IllegalArgumentException("identity size must be positive");
        return new OresMatrix(SimpleMatrix.identity(size), false, false);
    }

    public int rows() { return value.getNumRows(); }
    public int cols() { return value.getNumCols(); }
    public boolean isComplex() { return complex; }

    /** Conservative actor/mailbox quota footprint, independent of JVM object layout. */
    public long logicalBytes() {
        long elements = Math.multiplyExact((long) rows(), (long) cols());
        return Math.addExact(64L, Math.multiplyExact(elements, complex ? 16L : 8L));
    }

    public Object get(int row, int column) {
        checkBounds(row, column);
        double real = value.getReal(row, column);
        if (!complex) return real;
        return new OresComplex(real, value.getImaginary(row, column));
    }

    public OresMatrix add(OresMatrix other) {
        requireSameShape(other, "addition");
        MatrixPair pair = compatible(other);
        return new OresMatrix(pair.left.plus(pair.right), pair.complex, false);
    }

    public OresMatrix subtract(OresMatrix other) {
        requireSameShape(other, "subtraction");
        MatrixPair pair = compatible(other);
        return new OresMatrix(pair.left.minus(pair.right), pair.complex, false);
    }

    public OresMatrix matmul(OresMatrix other) {
        if (cols() != other.rows()) {
            throw new IllegalArgumentException(
                    "matrix multiplication shape mismatch: " + rows() + "x" + cols()
                            + " @ " + other.rows() + "x" + other.cols());
        }
        MatrixPair pair = compatible(other);
        return new OresMatrix(pair.left.mult(pair.right), pair.complex, false);
    }

    public OresVector matmul(OresVector other) {
        OresMatrix product = matmul(other.asMatrix());
        return OresVector.fromMatrix(product);
    }

    public OresMatrix scale(Object scalar) {
        OresComplex number = OresComplex.of(scalar);
        if (number.imaginary() == 0.0 && !complex) {
            return new OresMatrix(value.scale(number.real()), false, false);
        }
        SimpleMatrix promoted = asComplexCopy();
        return new OresMatrix(promoted.scaleComplex(number.real(), number.imaginary()), true, false);
    }

    public OresMatrix divide(Object scalar) {
        OresComplex number = OresComplex.of(scalar);
        if (number.imaginary() == 0.0 && !complex) {
            return new OresMatrix(value.divide(number.real()), false, false);
        }
        return scale(number.reciprocal());
    }

    public OresMatrix transpose() { return new OresMatrix(value.transpose(), complex, false); }

    public OresMatrix hermitian() {
        return complex
                ? new OresMatrix(value.transposeConjugate(), true, false)
                : transpose();
    }

    public OresMatrix inverse() {
        requireSquare("inverse");
        return new OresMatrix(value.invert(), complex, false);
    }

    public OresMatrix pseudoInverse() {
        return new OresMatrix(value.pseudoInverse(), complex, false);
    }

    public OresMatrix solve(OresMatrix rhs) {
        if (rows() != rhs.rows()) throw new IllegalArgumentException("solve requires A.rows == b.rows");
        MatrixPair pair = compatible(rhs);
        return new OresMatrix(pair.left.solve(pair.right), pair.complex, false);
    }

    public Object determinant() {
        requireSquare("determinant");
        if (!complex) return value.determinant();
        Complex_F64 result = value.determinantComplex();
        return new OresComplex(result.real, result.imaginary);
    }

    public Object trace() {
        if (!complex) return value.trace();
        Complex_F64 result = value.traceComplex();
        return new OresComplex(result.real, result.imaginary);
    }

    public double norm() { return value.normF(); }
    public double condition() { return value.conditionP2(); }

    public List<Object> diagonal() {
        int count = Math.min(rows(), cols());
        List<Object> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(get(i, i));
        return List.copyOf(result);
    }

    @Override public Iterable<?> sharedStateChildren() { return List.of(); }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("matrix[");
        for (int row = 0; row < rows(); row++) {
            if (row > 0) out.append(", ");
            out.append('[');
            for (int column = 0; column < cols(); column++) {
                if (column > 0) out.append(", ");
                out.append(get(row, column));
            }
            out.append(']');
        }
        return out.append(']').toString();
    }

    private MatrixPair compatible(OresMatrix other) {
        if (complex == other.complex) return new MatrixPair(value, other.value, complex);
        return new MatrixPair(asComplexCopy(), other.asComplexCopy(), true);
    }

    private SimpleMatrix asComplexCopy() {
        SimpleMatrix copy = value.copy();
        if (!complex) copy.convertToComplex();
        return copy;
    }

    private void requireSameShape(OresMatrix other, String operation) {
        if (rows() != other.rows() || cols() != other.cols()) {
            throw new IllegalArgumentException(operation + " requires equal matrix shapes");
        }
    }

    private void requireSquare(String operation) {
        if (rows() != cols()) throw new IllegalArgumentException(operation + " requires a square matrix");
    }

    private void checkBounds(int row, int column) {
        if (row < 0 || row >= rows() || column < 0 || column >= cols()) {
            throw new IndexOutOfBoundsException(
                    "matrix index (" + row + "," + column + ") outside " + rows() + "x" + cols());
        }
    }

    private static void requireScalar(Object value) {
        if (value instanceof BigDecimal) {
            throw new IllegalArgumentException(
                    "decimal matrix elements require a separate exact backend; refusing silent decimal -> f64 coercion");
        }
        if (!(value instanceof Number) && !(value instanceof OresComplex)) {
            throw new IllegalArgumentException("matrix elements must be real or complex numbers");
        }
    }

    private record MatrixPair(SimpleMatrix left, SimpleMatrix right, boolean complex) { }
}
