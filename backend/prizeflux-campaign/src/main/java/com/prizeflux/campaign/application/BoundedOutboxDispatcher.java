package com.prizeflux.campaign.application;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Owns only bounded dispatch and shutdown; acknowledgement and fencing stay in the Relay. */
@Component
@ConditionalOnProperty(name = "prizeflux.outbox.relay.enabled", havingValue = "true")
public final class BoundedOutboxDispatcher {
    private final int parallelism;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Duration waitBudget;

    @Autowired
    public BoundedOutboxDispatcher(@Value("${prizeflux.outbox.relay.parallelism:1}") int parallelism) {
        this(parallelism, Duration.ofSeconds(15));
    }

    BoundedOutboxDispatcher(int parallelism, Duration waitBudget) {
        if (parallelism < 1 || parallelism > 8) {
            throw new IllegalArgumentException("Outbox parallelism must be between 1 and 8");
        }
        this.parallelism = parallelism;
        if (waitBudget == null || waitBudget.isZero() || waitBudget.isNegative()) {
            throw new IllegalArgumentException("Outbox dispatch wait must be positive");
        }
        this.waitBudget = waitBudget;
        this.executor = parallelism == 1 ? null : new ThreadPoolExecutor(
                parallelism, parallelism, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(parallelism), task -> {
                    Thread thread = new Thread(task, "outbox-publish");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public int claimLimit(int configuredBatchSize) {
        return parallelism == 1 ? configuredBatchSize : Math.min(configuredBatchSize, parallelism);
    }

    public boolean isClosed() { return closed.get(); }

    public boolean isParallel() { return executor != null; }

    /** Caller serializes claim/dispatch rounds. Parallel rounds never enqueue more than one wave. */
    public void dispatch(List<OutboxEvent> events, Function<OutboxEvent, Boolean> publish) {
        if (closed.get()) { return; } // Claimed but unsent rows recover through their leases.
        if (executor == null) {
            for (OutboxEvent event : events) {
                if (closed.get() || !publish.apply(event)) { break; }
            }
            return;
        }
        if (events.size() > parallelism) {
            throw new IllegalArgumentException("Outbox dispatch exceeds one bounded wave");
        }
        List<Future<Boolean>> tasks = new ArrayList<>();
        long deadline = System.nanoTime() + waitBudget.toNanos();
        try {
            for (OutboxEvent event : events) { tasks.add(executor.submit(() -> publish.apply(event))); }
            RuntimeException firstFailure = null;
            boolean stopRequested = false;
            for (Future<Boolean> task : tasks) {
                try {
                    if (!task.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        stopRequested = true;
                    }
                } catch (ExecutionException failure) {
                    if (firstFailure == null) {
                        firstFailure = failure.getCause() instanceof RuntimeException original
                                ? original : new IllegalStateException("Outbox dispatch failed", failure.getCause());
                    }
                }
            }
            // Join the entire wave before allowing the next claim, even after a database failure.
            if (stopRequested) { close(); }
            if (firstFailure != null) { throw firstFailure; }
        } catch (InterruptedException failure) {
            // Do not claim another wave while cancelled tasks may still be returning from JDBC.
            close();
            tasks.forEach(task -> task.cancel(true));
            Thread.currentThread().interrupt();
        } catch (TimeoutException failure) {
            close();
            tasks.forEach(task -> task.cancel(true));
            throw new IllegalStateException("Outbox wave timed out; restart required and leases recover", failure);
        } catch (CancellationException failure) {
            tasks.forEach(task -> task.cancel(true));
            if (!closed.get()) { throw failure; }
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            boolean stopping = closed.get();
            close();
            tasks.forEach(task -> task.cancel(true));
            if (!stopping) { throw failure; }
        }
    }

    @PreDestroy
    public void close() {
        closed.set(true);
        if (executor != null) {
            for (Runnable task : executor.shutdownNow()) {
                if (task instanceof Future<?> future) { future.cancel(true); }
            }
        }
    }
}
