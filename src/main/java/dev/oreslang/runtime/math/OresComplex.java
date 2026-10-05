package dev.oreslang.runtime.math;

import dev.oreslang.runtime.ImmutableGuestValue;

import java.util.List;

/** Immutable complex128 value used by the Oreslang numeric runtime. */
public record OresComplex(double real, double imaginary) implements ImmutableGuestValue {
    public static final OresComplex ZERO = new OresComplex(0.0, 0.0);
    public static final OresComplex ONE = new OresComplex(1.0, 0.0);
    public static final OresComplex I = new OresComplex(0.0, 1.0);

    public static OresComplex of(Object value) {
        if (value instanceof OresComplex complex) return complex;
        if (value instanceof Number number) return new OresComplex(number.doubleValue(), 0.0);
        throw new IllegalArgumentException("expected a numeric or complex value");
    }

    public static OresComplex polar(double magnitude, double phaseRadians) {
        return new OresComplex(
                magnitude * Math.cos(phaseRadians),
                magnitude * Math.sin(phaseRadians));
    }

    public OresComplex add(OresComplex other) {
        return new OresComplex(real + other.real, imaginary + other.imaginary);
    }

    public OresComplex subtract(OresComplex other) {
        return new OresComplex(real - other.real, imaginary - other.imaginary);
    }

    public OresComplex multiply(OresComplex other) {
        return new OresComplex(
                real * other.real - imaginary * other.imaginary,
                real * other.imaginary + imaginary * other.real);
    }

    /**
     * Smith-style complex division avoids the avoidable overflow in
     * (c*c + d*d) used by the naive formula.
     */
    public OresComplex divide(OresComplex other) {
        double c = other.real;
        double d = other.imaginary;
        if (c == 0.0 && d == 0.0) {
            return new OresComplex(real / 0.0, imaginary / 0.0);
        }
        if (Math.abs(c) >= Math.abs(d)) {
            double ratio = d / c;
            double denom = c + d * ratio;
            return new OresComplex(
                    (real + imaginary * ratio) / denom,
                    (imaginary - real * ratio) / denom);
        }
        double ratio = c / d;
        double denom = d + c * ratio;
        return new OresComplex(
                (real * ratio + imaginary) / denom,
                (imaginary * ratio - real) / denom);
    }

    public OresComplex reciprocal() {
        return ONE.divide(this);
    }

    public OresComplex negate() {
        return new OresComplex(-real, -imaginary);
    }

    public OresComplex conjugate() {
        return new OresComplex(real, -imaginary);
    }

    public double abs() {
        return Math.hypot(real, imaginary);
    }

    public double absSquared() {
        return real * real + imaginary * imaginary;
    }

    public double phase() {
        return Math.atan2(imaginary, real);
    }

    public OresComplex exp() {
        double scale = Math.exp(real);
        return new OresComplex(scale * Math.cos(imaginary), scale * Math.sin(imaginary));
    }

    public OresComplex log() {
        return new OresComplex(Math.log(abs()), phase());
    }

    public OresComplex sqrt() {
        if (real == 0.0 && imaginary == 0.0) return ZERO;
        double magnitude = abs();
        double realPart = Math.sqrt((magnitude + real) / 2.0);
        double imaginaryPart = Math.copySign(
                Math.sqrt(Math.max(0.0, (magnitude - real) / 2.0)),
                imaginary);
        return new OresComplex(realPart, imaginaryPart);
    }

    public OresComplex sin() {
        return new OresComplex(
                Math.sin(real) * Math.cosh(imaginary),
                Math.cos(real) * Math.sinh(imaginary));
    }

    public OresComplex cos() {
        return new OresComplex(
                Math.cos(real) * Math.cosh(imaginary),
                -Math.sin(real) * Math.sinh(imaginary));
    }

    @Override
    public long logicalBytes() {
        return 32L;
    }

    @Override
    public Iterable<?> sharedStateChildren() {
        return List.of();
    }

    @Override
    public String toString() {
        if (imaginary == 0.0) return Double.toString(real);
        if (real == 0.0) return imaginary + "i";
        return real + (imaginary < 0.0 ? "" : "+") + imaginary + "i";
    }
}
