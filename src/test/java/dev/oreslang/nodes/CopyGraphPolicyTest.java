package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CopyGraphPolicyTest {

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
