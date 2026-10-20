package dev.oreslang.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresHostFailureTransferTest {
    @AfterEach
    void clear() {
        OresHostFailureTransfer.clear();
    }

    @Test
    void publishOutsideCaptureScopeDoesNotRetainFailure() {
        RuntimeException failure = new RuntimeException("ignored");
        OresHostFailureTransfer.publish(failure);
        assertNull(OresHostFailureTransfer.consume());
    }

    @Test
    void captureTransfersFailureExactlyOnce() {
        RuntimeException failure = new RuntimeException("boom");
        try (OresHostFailureTransfer.Capture ignored =
                OresHostFailureTransfer.capture()) {
            OresHostFailureTransfer.publish(failure);
            assertSame(failure, OresHostFailureTransfer.consume());
            assertNull(OresHostFailureTransfer.consume());
        }
        assertNull(OresHostFailureTransfer.consume());
    }

    @Test
    void nestedCaptureRestoresOuterFailureWithoutCrossContamination() {
        RuntimeException outer = new RuntimeException("outer");
        RuntimeException inner = new RuntimeException("inner");

        try (OresHostFailureTransfer.Capture outerCapture =
                OresHostFailureTransfer.capture()) {
            OresHostFailureTransfer.publish(outer);

            try (OresHostFailureTransfer.Capture innerCapture =
                    OresHostFailureTransfer.capture()) {
                assertNull(OresHostFailureTransfer.consume());
                OresHostFailureTransfer.publish(inner);
                assertSame(inner, OresHostFailureTransfer.consume());
            }

            assertSame(outer, OresHostFailureTransfer.consume());
        }

        assertNull(OresHostFailureTransfer.consume());
    }
}
