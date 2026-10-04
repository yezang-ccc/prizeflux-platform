package com.prizeflux.campaign.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class BoundedOutboxDispatcherTest {
    private static List<OutboxEvent> events(int count) {
        return IntStream.range(0, count).mapToObj(i ->
                new OutboxEvent("event-" + i, "DrawQueued", i + 1, 1, "{}", 1)).toList();
    }

    @Test
    void serialDefaultRetainsBatchAndStopsAtInterruptedAction() {
        var dispatcher = new BoundedOutboxDispatcher(1);
        assertThat(dispatcher.claimLimit(50)).isEqualTo(50);
        var calls = new AtomicInteger();
        dispatcher.dispatch(events(3), event -> { calls.incrementAndGet(); return false; });
        assertThat(calls.get()).isEqualTo(1);
        dispatcher.close();
    }

    @Test
    void parallelClaimAndQueueAreBoundedToOneWave() throws Exception {
        var dispatcher = new BoundedOutboxDispatcher(3);
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        try {
            assertThat(dispatcher.claimLimit(50)).isEqualTo(3);
            assertThat(dispatcher.claimLimit(2)).isEqualTo(2);
            var run = CompletableFuture.runAsync(() -> dispatcher.dispatch(events(3), event -> {
                entered.countDown();
                try { return release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(run.isDone()).isFalse();
            release.countDown();
            run.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> dispatcher.dispatch(events(4), event -> true))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally { release.countDown(); dispatcher.close(); }
    }

    @Test
    void partialActionFailureWaitsForOtherEventAndPreservesOriginalFailure() {
        var dispatcher = new BoundedOutboxDispatcher(2);
        var completed = new AtomicInteger();
        var original = new IllegalStateException("database unavailable");
        try {
            assertThatThrownBy(() -> dispatcher.dispatch(events(2), event -> {
                if (event.eventId().equals("event-0")) { throw original; }
                completed.incrementAndGet();
                return true;
            })).isSameAs(original);
            assertThat(completed.get()).isEqualTo(1);
        } finally { dispatcher.close(); }
    }

    @Test
    void closeInterruptsActiveWorkAndRejectsFurtherDispatch() throws Exception {
        var dispatcher = new BoundedOutboxDispatcher(2);
        var entered = new CountDownLatch(2);
        var interrupted = new AtomicInteger();
        var run = CompletableFuture.runAsync(() -> dispatcher.dispatch(events(2), event -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return true; }
            catch (InterruptedException failure) { interrupted.incrementAndGet(); return false; }
        }));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.close();
            run.get(5, TimeUnit.SECONDS);
            assertThat(interrupted.get()).isEqualTo(2);
            var calls = new AtomicInteger();
            dispatcher.dispatch(events(1), event -> { calls.incrementAndGet(); return true; });
            assertThat(calls.get()).isZero();
        } finally { dispatcher.close(); }
    }

    @Test
    void invalidParallelismIsRejectedBeforeCreatingThreads() {
        assertThatThrownBy(() -> new BoundedOutboxDispatcher(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedOutboxDispatcher(9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void waveTimeoutStopsFutureClaimsAndCancelsBoundedWork() {
        var dispatcher = new BoundedOutboxDispatcher(2, java.time.Duration.ofMillis(100));
        try {
            assertThatThrownBy(() -> dispatcher.dispatch(events(2), event -> {
                try { new CountDownLatch(1).await(); return true; }
                catch (InterruptedException failure) { return false; }
            })).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(dispatcher.isClosed()).isTrue();
        } finally { dispatcher.close(); }
    }

    @Test
    void interruptedEventStopsNewWavesAfterJoiningAlreadyDispatchedPeers() {
        var dispatcher = new BoundedOutboxDispatcher(2);
        var completed = new AtomicInteger();
        try {
            dispatcher.dispatch(events(2), event -> {
                if (event.eventId().equals("event-0")) { return false; }
                completed.incrementAndGet();
                return true;
            });
            assertThat(completed.get()).isEqualTo(1);
            assertThat(dispatcher.isClosed()).isTrue();
        } finally { dispatcher.close(); }
    }
}
