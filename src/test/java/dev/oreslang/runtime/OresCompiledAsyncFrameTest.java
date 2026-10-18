package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class OresCompiledAsyncFrameTest {

    @Test
    void compiledFrameUsesPcAcrossHardAwaitBoundary() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> completed = OresFuture.completed(41);
            AtomicLong firstDispatch = new AtomicLong();

            final class Frame extends OresCompiledAsyncFrame<Integer> {
                // Representative compiler-generated suspension-live field.
                private Object retained;

                // Representative reference that generated liveness clearing
                // proves dead before the await.
                private Object dead;

                @Override
                public OresScheduler.Step<Integer> resume(
                        OresScheduler.Resume resume) {
                    for (;;) {
                        switch (pc) {
                            case 0 -> {
                                requireInitial(resume);
                                firstDispatch.set(
                                        OresScheduler.currentDispatchId());

                                retained = new Object();
                                dead = new Object();

                                // Generated from clearReferenceSlots.
                                dead = null;

                                return suspend(1, completed);
                            }

                            case 1 -> {
                                requireResumed(resume);
                                assertNotEquals(
                                        firstDispatch.get(),
                                        OresScheduler.currentDispatchId(),
                                        "compiled await must re-enter through a fresh dispatch");
                                assertNull(dead);
                                assertNotNull(retained);

                                Integer value = resumedValue(resume);

                                // Last-use clearing after resumption.
                                retained = null;
                                assertNull(retained);

                                return done(value + 1);
                            }

                            default -> throw new IllegalStateException(
                                    "invalid compiled frame pc " + pc);
                        }
                    }
                }
            }

            Frame frame = new Frame();
            OresFuture<Integer> result = scheduler.start(frame);

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, frame.programCounter());
        }
    }

    @Test
    void compiledFramePropagatesAwaitFailureByDefault() {
        IllegalStateException boom = new IllegalStateException("boom");

        final class Frame extends OresCompiledAsyncFrame<Integer> {
            @Override
            public OresScheduler.Step<Integer> resume(
                    OresScheduler.Resume resume) {
                pc = 1;
                return done(resumedValue(resume));
            }
        }

        Frame frame = new Frame();
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> frame.resume(new OresScheduler.Resume(
                        false,
                        null,
                        boom)));

        assertSame(boom, failure);
    }
}
