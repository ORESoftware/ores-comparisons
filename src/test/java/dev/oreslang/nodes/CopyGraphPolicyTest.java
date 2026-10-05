package dev.oreslang.nodes;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CopyGraphPolicyTest {
    private static final class MutableBigInteger extends BigInteger {
        private int mutableState;

        MutableBigInteger() {
            super("7");
        }

        void mutate() {
            mutableState++;
        }
    }

    @Test
    void inspectableGuestGraphIsAccepted() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "Alex");
        value.put("items", List.of(1L, 2L, 3L));

        assertDoesNotThrow(() -> OresEvalRootNode.requireInspectableCopyGraph(value));
    }

    @Test
    void opaqueHostReferenceFailsClosed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresEvalRootNode.requireInspectableCopyGraph(new Object()));

        assertTrue(error.getMessage().contains("opaque foreign/host reference"), error.getMessage());
    }

    @Test
    void mutableNumberSubclassFailsClosed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresEvalRootNode.requireInspectableCopyGraph(new AtomicInteger(7)));

        assertTrue(error.getMessage().contains("opaque foreign/host reference"), error.getMessage());
    }

    @Test
    void subclassOfNominallyImmutableBigNumberFailsClosed() {
        MutableBigInteger value = new MutableBigInteger();
        value.mutate();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresEvalRootNode.requireInspectableCopyGraph(value));

        assertTrue(error.getMessage().contains("opaque foreign/host reference"), error.getMessage());
    }

    @Test
    void publicActorSharedWrapperIsNotTrustedByCopyVerifier() {
        ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
        ActorRuntime.Shared<ArrayList<Integer>> shared = new ActorRuntime.Shared<>(mutable);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresEvalRootNode.requireInspectableCopyGraph(shared));

        assertTrue(error.getMessage().contains("ActorRuntime.Shared"), error.getMessage());
    }

    @Test
    void opaqueHostReferenceNestedInGuestGraphFailsClosed() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("safe", "value");
        value.put("foreign", new Object());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresEvalRootNode.requireInspectableCopyGraph(value));

        assertTrue(error.getMessage().contains("explicit immutable/copy adapter"), error.getMessage());
    }
}
