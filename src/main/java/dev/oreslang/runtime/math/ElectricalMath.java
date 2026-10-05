package dev.oreslang.runtime.math;

/** Electrical-engineering primitives built on Oreslang complex128 values. */
public final class ElectricalMath {
    private ElectricalMath() { }

    public static OresComplex phasor(double magnitude, double phaseRadians) {
        return OresComplex.polar(magnitude, phaseRadians);
    }

    public static OresComplex phasorDegrees(double magnitude, double phaseDegrees) {
        return phasor(magnitude, Math.toRadians(phaseDegrees));
    }

    public static OresComplex resistance(double ohms) {
        return new OresComplex(ohms, 0.0);
    }

    public static OresComplex inductiveImpedance(double omegaRadiansPerSecond, double henries) {
        return new OresComplex(0.0, omegaRadiansPerSecond * henries);
    }

    public static OresComplex capacitiveImpedance(double omegaRadiansPerSecond, double farads) {
        if (omegaRadiansPerSecond == 0.0 || farads == 0.0) {
            throw new IllegalArgumentException("capacitive impedance requires non-zero omega and capacitance");
        }
        return new OresComplex(0.0, -1.0 / (omegaRadiansPerSecond * farads));
    }

    public static OresComplex admittance(Object impedance) {
        return OresComplex.of(impedance).reciprocal();
    }

    public static OresComplex parallel(Object left, Object right) {
        OresComplex a = OresComplex.of(left);
        OresComplex b = OresComplex.of(right);
        return a.multiply(b).divide(a.add(b));
    }

    public static double angularFrequency(double hertz) { return 2.0 * Math.PI * hertz; }
    public static double db20(double ratio) { return 20.0 * Math.log10(Math.abs(ratio)); }
    public static double db10(double ratio) { return 10.0 * Math.log10(Math.abs(ratio)); }
    public static double fromDb20(double decibels) { return Math.pow(10.0, decibels / 20.0); }
    public static double fromDb10(double decibels) { return Math.pow(10.0, decibels / 10.0); }
}
