package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ImmutableGuestValue;
import dev.oreslang.runtime.math.ElectricalMath;
import dev.oreslang.runtime.math.OresComplex;
import dev.oreslang.runtime.math.OresMatrix;
import dev.oreslang.runtime.math.OresVector;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MathLanguageTest {
    @Test
    void matrixVectorAndElectricalSyntaxTypeChecks() {
        Ast.Program program = Parser.parse("""
                pub fnc transform() => Vector<float> {
                  val a = matrix[[1.0, 2.0], [3.0, 4.0]];
                  val x = vector[5.0, 6.0];
                  return a @ x;
                }

                pub fnc capacitor() => complex {
                  return ee.z_c(ee.omega(60.0), 0.0001);
                }
                """);

        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void matrixMultiplicationUsesAtAndStarRemainsScalarScaling() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc scale() => Matrix<float> {
                  val a = matrix[[1.0, 2.0], [3.0, 4.0]];
                  return 2.0 * a;
                }
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad() => Matrix<float> {
                          val a = matrix[[1.0, 2.0], [3.0, 4.0]];
                          return a * a;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("uses '@'"));
    }

    @Test
    void namedImportsSupportKotlinStyleAliases() {
        Ast.Program program = Parser.parse("""
                import fnc {solve as linear_solve, stop as halt} from "./solver.ores";
                import class Matrix as DenseMatrix from "./types.ores";

                pub fnc main() => void {
                  return;
                }
                """);

        Ast.ImportDecl functions = program.imports().get(0);
        assertEquals("linear_solve", functions.localName("solve"));
        assertEquals("halt", functions.localName("stop"));
        assertEquals("solve", functions.importedName("linear_solve"));

        Ast.ImportDecl classes = program.imports().get(1);
        assertEquals("DenseMatrix", classes.localName("Matrix"));
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void complexAndMatrixRuntimePrimitivesAreNumericallySound() {
        OresComplex z = new OresComplex(3.0, 4.0);
        assertEquals(5.0, z.abs(), 1e-12);
        assertEquals(new OresComplex(3.0, -4.0), z.conjugate());

        OresMatrix a = OresMatrix.fromRows(List.of(
                List.of(1.0, 2.0),
                List.of(3.0, 4.0)));
        OresMatrix b = OresMatrix.fromRows(List.of(
                List.of(5.0, 6.0),
                List.of(7.0, 8.0)));
        OresMatrix product = a.matmul(b);
        assertEquals(19.0, (double) product.get(0, 0), 1e-12);
        assertEquals(22.0, (double) product.get(0, 1), 1e-12);
        assertEquals(43.0, (double) product.get(1, 0), 1e-12);
        assertEquals(50.0, (double) product.get(1, 1), 1e-12);

        OresVector x = OresVector.fromValues(List.of(5.0, 6.0));
        OresVector y = a.matmul(x);
        assertEquals(17.0, (double) y.get(0), 1e-12);
        assertEquals(39.0, (double) y.get(1), 1e-12);
    }

    @Test
    void integerDenseLiteralsPromoteToFloatAndDecimalDenseTypesAreRejected() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc promote() => Matrix<float> {
                  return matrix[[1, 2], [3, 4]];
                }
                """)));

        IllegalArgumentException decimal = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc exact(Matrix<decimal> a) => void {
                          return;
                        }
                        """)));
        assertTrue(decimal.getMessage().contains("decimal matrices require a separate exact backend"));
    }

    @Test
    void runtimeMatrixConstructionAlsoRejectsDecimalPrecisionLoss() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresMatrix.fromRows(java.util.List.of(
                        java.util.List.of(new java.math.BigDecimal("0.1")))));
        assertTrue(error.getMessage().contains("decimal -> f64"));
    }

    @Test
    void mixedRealComplexLinearAlgebraPromotesToComplex() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc mixed() => Matrix<complex> {
                  val real = matrix[[1.0, 0.0], [0.0, 1.0]];
                  val complex_rhs = matrix[[1.0 + 2i], [3.0 - 4i]];
                  return real.solve(complex_rhs);
                }
                """)));
    }

    @Test
    void immutableGuestValueTrustBoundaryIsClosedToAuditedRuntimeTypes() {
        assertTrue(ImmutableGuestValue.class.isSealed());
        var permitted = java.util.Arrays.stream(ImmutableGuestValue.class.getPermittedSubclasses())
                .map(Class::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(
                java.util.Set.of(
                        OresComplex.class.getName(),
                        OresMatrix.class.getName(),
                        OresVector.class.getName()),
                permitted);
    }

    @Test
    void immutableMathValuesCanCrossTrustedActorTransportWithoutMutableHostEscape() {
        OresMatrix matrix = OresMatrix.fromRows(List.of(
                List.of(1.0, 2.0),
                List.of(3.0, 4.0)));
        OresVector vector = OresVector.fromValues(List.of(5.0, 6.0));
        OresComplex complex = new OresComplex(3.0, 4.0);

        assertSame(matrix, ActorRuntime.freeze(matrix));
        assertSame(vector, ActorRuntime.freeze(vector));
        assertSame(complex, ActorRuntime.freeze(complex));
        assertTrue(matrix.logicalBytes() > complex.logicalBytes());
        assertTrue(vector.logicalBytes() > complex.logicalBytes());
    }

    @Test
    void aliasedImportsRejectDuplicateLocalNames() {
        Ast.Program program = Parser.parse("""
                import fnc {solve as op, invert as op} from "./solver.ores";
                pub fnc main() => void { return; }
                """);

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(duplicate.getMessage().contains("duplicate imported name 'op'"));
    }

    @Test
    void electricalHelpersUseComplexImpedanceSemantics() {
        double omega = ElectricalMath.angularFrequency(60.0);
        OresComplex capacitor = ElectricalMath.capacitiveImpedance(omega, 100e-6);
        assertEquals(0.0, capacitor.real(), 1e-12);
        assertEquals(-26.525823848649225, capacitor.imaginary(), 1e-12);

        OresComplex inductor = ElectricalMath.inductiveImpedance(omega, 10e-3);
        assertEquals(0.0, inductor.real(), 1e-12);
        assertEquals(3.7699111843077517, inductor.imaginary(), 1e-12);
    }
}
