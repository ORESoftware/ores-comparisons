package dev.oreslang;

import dev.oreslang.runtime.ChannelRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ChannelRuntimeTest {
    @Test
    void bufferedChannelIsBoundedAndTrySendReturnsOwnershipOnFailure() {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(1);

        assertTrue(channel.trySend("one").sent());

        var full = channel.trySend("two");
        assertFalse(full.sent());
        assertEquals("two", full.unsent());

        var received = channel.tryReceive();
        assertTrue(received.received());
        assertEquals("one", received.value());
        assertFalse(channel.tryReceive().received());
    }

    @Test
    void rendezvousSendCompletesOnlyAfterReceiveClaimsIt() throws Exception {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(0);

        CompletableFuture<Void> sent = channel.send("payload").toCompletableFuture();
        assertFalse(sent.isDone());
        assertEquals(1, channel.waitingSenders());

        assertEquals("payload", channel.receive().toCompletableFuture().get(1, TimeUnit.SECONDS));
        sent.get(1, TimeUnit.SECONDS);
        assertEquals(0, channel.waitingSenders());
    }

    @Test
    void cancellingPendingSenderPreventsLaterDelivery() {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(0);

        CompletableFuture<Void> pending = channel.send("payload").toCompletableFuture();
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertEquals(0, channel.waitingSenders());

        var polled = channel.tryReceive();
        assertFalse(polled.received());
    }

    @Test
    void cancellingPendingReceiverPreventsItFromStealingNextValue() {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(0);

        CompletableFuture<String> pending = channel.receive().toCompletableFuture();
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertEquals(0, channel.waitingReceivers());

        var unsent = channel.trySend("payload");
        assertFalse(unsent.sent());
        assertEquals("payload", unsent.unsent());
    }

    @Test
    void selectDefaultDoesNotLeaveRegistrationsBehind() throws Exception {
        ChannelRuntime runtime = new ChannelRuntime();
        var a = runtime.<String>channel(0);
        var b = runtime.<String>channel(0);

        var selected = runtime.select(
                List.of(new ChannelRuntime.ReceiveCase<>(a), new ChannelRuntime.ReceiveCase<>(b)),
                true,
                0).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertTrue(selected.defaulted());
        assertEquals(-1, selected.caseIndex());
        assertEquals(0, a.waitingReceivers());
        assertEquals(0, b.waitingReceivers());
    }

    @Test
    void selectReceiveCommitsExactlyOneReadyCase() throws Exception {
        ChannelRuntime runtime = new ChannelRuntime();
        var a = runtime.<String>channel(1);
        var b = runtime.<String>channel(1);
        assertTrue(a.trySend("a").sent());
        assertTrue(b.trySend("b").sent());

        var selected = runtime.select(
                List.of(new ChannelRuntime.ReceiveCase<>(a), new ChannelRuntime.ReceiveCase<>(b)),
                false,
                1).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(1, selected.caseIndex());
        assertEquals("b", selected.receivedValue());
        assertEquals("a", a.tryReceive().value());
        assertFalse(b.tryReceive().received());
    }

    @Test
    void fairnessCursorChoosesAmongSimultaneouslyReadySendCases() throws Exception {
        ChannelRuntime runtime = new ChannelRuntime();
        var a = runtime.<String>channel(1);
        var b = runtime.<String>channel(1);

        var selected = runtime.select(
                List.of(
                        new ChannelRuntime.SendCase<>(a, "a"),
                        new ChannelRuntime.SendCase<>(b, "b")),
                false,
                1).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(1, selected.caseIndex());
        assertFalse(selected.defaulted());
        assertFalse(a.tryReceive().received());
        assertEquals("b", b.tryReceive().value());
    }

    @Test
    void blockingSelectCanRendezvousWithAnotherBlockingSelect() throws Exception {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(0);

        CompletableFuture<ChannelRuntime.SelectResult> sender = runtime.select(
                List.of(new ChannelRuntime.SendCase<>(channel, "payload")),
                false,
                0).toCompletableFuture();
        assertFalse(sender.isDone());
        assertEquals(1, channel.waitingSenders());

        CompletableFuture<ChannelRuntime.SelectResult> receiver = runtime.select(
                List.of(new ChannelRuntime.ReceiveCase<>(channel)),
                false,
                0).toCompletableFuture();

        var received = receiver.get(1, TimeUnit.SECONDS);
        var sent = sender.get(1, TimeUnit.SECONDS);
        assertEquals(0, sent.caseIndex());
        assertNull(sent.receivedValue());
        assertEquals(0, received.caseIndex());
        assertEquals("payload", received.receivedValue());
        assertEquals(0, channel.waitingSenders());
        assertEquals(0, channel.waitingReceivers());
    }

    @Test
    void oneSelectCannotRendezvousWithItself() {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(0);

        CompletableFuture<ChannelRuntime.SelectResult> pending = runtime.select(
                List.of(
                        new ChannelRuntime.SendCase<>(channel, "payload"),
                        new ChannelRuntime.ReceiveCase<>(channel)),
                false,
                0).toCompletableFuture();

        assertFalse(pending.isDone());
        assertEquals(1, channel.waitingSenders());
        assertEquals(1, channel.waitingReceivers());

        assertTrue(pending.cancel(false));
        assertEquals(0, channel.waitingSenders());
        assertEquals(0, channel.waitingReceivers());
    }

    @Test
    void cancellingSelectAtomicallyUnregistersEveryCase() {
        ChannelRuntime runtime = new ChannelRuntime();
        var a = runtime.<String>channel(0);
        var b = runtime.<String>channel(0);

        CompletableFuture<ChannelRuntime.SelectResult> pending = runtime.select(
                List.of(new ChannelRuntime.ReceiveCase<>(a), new ChannelRuntime.ReceiveCase<>(b)),
                false,
                0).toCompletableFuture();

        assertEquals(1, a.waitingReceivers());
        assertEquals(1, b.waitingReceivers());
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertEquals(0, a.waitingReceivers());
        assertEquals(0, b.waitingReceivers());
    }

    @Test
    void selectRejectsChannelsFromDifferentCoordinationDomains() {
        ChannelRuntime left = new ChannelRuntime();
        ChannelRuntime right = new ChannelRuntime();
        var a = left.<String>channel(0);
        var b = right.<String>channel(0);

        assertThrows(IllegalArgumentException.class, () -> left.select(
                List.of(new ChannelRuntime.ReceiveCase<>(a), new ChannelRuntime.ReceiveCase<>(b)),
                false,
                0));
    }

    @Test
    void hostCannotForgePendingChannelCompletion() {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(0);

        CompletableFuture<String> receive = channel.receive().toCompletableFuture();
        assertThrows(UnsupportedOperationException.class, () -> receive.complete("forged"));
        assertThrows(UnsupportedOperationException.class,
                () -> receive.completeExceptionally(new IllegalStateException("forged")));
        assertThrows(UnsupportedOperationException.class,
                () -> receive.completeAsync(() -> "forged"));
        assertThrows(UnsupportedOperationException.class,
                () -> receive.completeOnTimeout("forged", 1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class,
                () -> receive.orTimeout(1, TimeUnit.MILLISECONDS));
        assertFalse(receive.isDone());

        var sent = channel.trySend("real");
        assertTrue(sent.sent());
        assertEquals("real", receive.join());
    }

    @Test
    void hostCannotForgePendingSelectCompletion() {
        ChannelRuntime runtime = new ChannelRuntime();
        var channel = runtime.<String>channel(0);

        CompletableFuture<ChannelRuntime.SelectResult> selected = runtime.select(
                List.of(new ChannelRuntime.ReceiveCase<>(channel)),
                false,
                0).toCompletableFuture();

        assertThrows(UnsupportedOperationException.class,
                () -> selected.complete(ChannelRuntime.SelectResult.defaultResult()));
        assertFalse(selected.isDone());

        assertTrue(channel.trySend("real").sent());
        assertEquals("real", selected.join().receivedValue());
    }


    @Test
    void runtimeBoundsChannelCapacityAndSelectWidth() {
        ChannelRuntime runtime = new ChannelRuntime(new ChannelRuntime.Limits(2, 2, 4));

        assertEquals(2, runtime.limits().maxChannelCapacity());
        assertDoesNotThrow(() -> runtime.channel(2));
        assertThrows(IllegalArgumentException.class, () -> runtime.channel(3));

        var a = runtime.<String>channel(0);
        var b = runtime.<String>channel(0);
        var c = runtime.<String>channel(0);

        assertThrows(IllegalArgumentException.class, () -> runtime.select(
                List.of(
                        new ChannelRuntime.ReceiveCase<>(a),
                        new ChannelRuntime.ReceiveCase<>(b),
                        new ChannelRuntime.ReceiveCase<>(c)),
                false,
                0));
        assertEquals(0, a.waitingReceivers());
        assertEquals(0, b.waitingReceivers());
        assertEquals(0, c.waitingReceivers());
    }

    @Test
    void waiterLimitAppliesToBothSendersAndReceivers() {
        ChannelRuntime runtime = new ChannelRuntime(new ChannelRuntime.Limits(1, 4, 1));

        var sendChannel = runtime.<String>channel(0);
        CompletableFuture<Void> firstSend = sendChannel.send("one").toCompletableFuture();
        assertEquals(1, sendChannel.waitingSenders());
        assertThrows(IllegalStateException.class, () -> sendChannel.send("two"));
        assertEquals(1, sendChannel.waitingSenders());
        assertTrue(firstSend.cancel(false));

        var receiveChannel = runtime.<String>channel(0);
        CompletableFuture<String> firstReceive = receiveChannel.receive().toCompletableFuture();
        assertEquals(1, receiveChannel.waitingReceivers());
        assertThrows(IllegalStateException.class, receiveChannel::receive);
        assertEquals(1, receiveChannel.waitingReceivers());
        assertTrue(firstReceive.cancel(false));
    }

    @Test
    void selectWaiterLimitFailureRollsBackEveryRegistration() {
        ChannelRuntime runtime = new ChannelRuntime(new ChannelRuntime.Limits(1, 4, 1));
        var channel = runtime.<String>channel(0);

        assertThrows(IllegalStateException.class, () -> runtime.select(
                List.of(
                        new ChannelRuntime.ReceiveCase<>(channel),
                        new ChannelRuntime.ReceiveCase<>(channel)),
                false,
                0));

        assertEquals(0, channel.waitingReceivers(),
                "select capacity admission must be atomic across all cases");
        assertEquals(0, channel.waitingSenders());
    }

}
