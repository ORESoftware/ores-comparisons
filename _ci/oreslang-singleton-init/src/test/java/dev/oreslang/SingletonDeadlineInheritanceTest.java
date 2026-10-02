package dev.oreslang;

import dev.oreslang.runtime.ProcessSingletonRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SingletonDeadlineInheritanceTest {
    @Test
    void nestedSingletonCallCannotExtendOuterDeadline() throws Exception {
        var outer = ProcessSingletonRegistry.getOrCreate(
                "deadline-outer:" + UUID.randomUUID(), Object::new);
        var inner = ProcessSingletonRegistry.getOrCreate(
                "deadline-inner:" + UUID.randomUUID(), Object::new);

        CompletableFuture<Object> call = outer.call(
                List.of(), 8, Duration.ofMillis(100),
                (outerState, ignored) -> inner.call(
                        List.of(), 8, Duration.ofSeconds(5),
                        (innerState, ignoredAgain) -> {
                            while (true) {
                                ProcessSingletonRegistry.checkExecutionBudget();
                            }
                        }).toCompletableFuture().join())
                .toCompletableFuture();

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> call.get(1, TimeUnit.SECONDS));
        assertTrue(causeChainContains(failure, "wall-time budget")
                        || causeChainContains(failure, "expired in mailbox"),
                String.valueOf(failure));
    }

    private static boolean causeChainContains(Throwable failure, String text) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).contains(text)) return true;
        }
        return false;
    }
}
