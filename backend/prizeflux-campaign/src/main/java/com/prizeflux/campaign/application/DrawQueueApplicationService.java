package com.prizeflux.campaign.application;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/** 用户异步抽奖的受理、本人查询、可恢复领取与幂等处理用例。 */
@Service
@ConditionalOnProperty(name = {"prizeflux.persistence.campaign.enabled", "prizeflux.kafka.draw-queue-enabled"},
        havingValue = "true")
public final class DrawQueueApplicationService {
    private static final Logger log = LoggerFactory.getLogger(DrawQueueApplicationService.class);

    private final DrawQueueRepository queue;
    private final DrawQueueTrafficGuard trafficGuard;
    private final DrawQueueCapacityGuard capacityGuard;
    private final DrawQueueTelemetry telemetry;
    private final ActorProvider actors;
    private final DrawApplicationService draws;
    private final TransactionTemplate transactions;
    private final TaskScheduler scheduler;
    private final Duration leaseDuration;
    private final Duration retryBase;
    private final Duration retryMax;
    private final int maxAttempts;
    private final int scanBatchSize;

    public DrawQueueApplicationService(DrawQueueRepository queue, DrawQueueTrafficGuard trafficGuard,
            DrawQueueCapacityGuard capacityGuard, DrawQueueTelemetry telemetry, ActorProvider actors,
            DrawApplicationService draws, PlatformTransactionManager transactionManager,
            @Qualifier("drawQueueTaskScheduler") TaskScheduler scheduler,
            @Value("${prizeflux.kafka.draw-queue-lease:PT1M}") Duration leaseDuration,
            @Value("${prizeflux.kafka.draw-queue-retry-base:PT2S}") Duration retryBase,
            @Value("${prizeflux.kafka.draw-queue-retry-max:PT1M}") Duration retryMax,
            @Value("${prizeflux.kafka.draw-queue-max-attempts:5}") int maxAttempts,
            @Value("${prizeflux.kafka.draw-queue-scan-batch-size:100}") int scanBatchSize) {
        if (leaseDuration.toMillis() < 3000
                || retryBase.isZero() || retryBase.isNegative()
                || retryMax.compareTo(retryBase) < 0 || maxAttempts < 1
                || scanBatchSize < 1 || scanBatchSize > 1000) {
            throw new IllegalArgumentException("Invalid draw queue execution configuration");
        }
        this.queue = queue;
        this.trafficGuard = trafficGuard;
        this.capacityGuard = capacityGuard;
        this.telemetry = telemetry;
        this.actors = actors;
        this.draws = draws;
        this.transactions = new TransactionTemplate(transactionManager);
        this.scheduler = scheduler;
        this.leaseDuration = leaseDuration;
        this.retryBase = retryBase;
        this.retryMax = retryMax;
        this.maxAttempts = maxAttempts;
        this.scanBatchSize = scanBatchSize;
    }

    public DrawQueueView enqueue(DrawCommand command) {
        long started = System.nanoTime();
        DrawQueueTelemetry.SubmissionOutcome outcome = DrawQueueTelemetry.SubmissionOutcome.FAILED;
        try {
            if (command == null) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            AuthenticatedActor actor = actors.currentActor();
            if (actor.role() != AppUser.Role.USER) throw new BusinessException(ErrorCode.FORBIDDEN);
            byte[] fingerprint = fingerprint(command.campaignId());
            Optional<DrawQueueRepository.Entry> existing = queue.findByUserAndKey(actor.userId(), command.requestKey());
            if (existing.isPresent()) {
                outcome = DrawQueueTelemetry.SubmissionOutcome.REPLAYED;
                return replay(existing.get(), fingerprint);
            }
            DrawQueueTrafficDecision traffic = trafficGuard.checkSubmission(actor.userId(), command.campaignId());
            if (!traffic.allowed()) throw new DrawQueueRateLimitException(Math.max(1, traffic.retryAfterSeconds()));
            try {
                capacityGuard.check(queue.pressureSnapshot());
            } catch (DrawQueueProtectionUnavailableException unavailable) {
                outcome = admissionOutcome(unavailable);
                throw unavailable;
            }
            try {
                long id = transactions.execute(status -> queue.enqueue(actor.userId(), command.campaignId(),
                        command.requestKey(), fingerprint));
                DrawQueueView view = queue.findById(id).map(DrawQueueView::from)
                        .orElseThrow(() -> new IllegalStateException("Queued draw disappeared after commit"));
                outcome = DrawQueueTelemetry.SubmissionOutcome.ACCEPTED;
                return view;
            } catch (DuplicateKeyException duplicate) {
                Optional<DrawQueueRepository.Entry> raced = queue.findByUserAndKey(actor.userId(),
                        command.requestKey());
                if (raced.isPresent()) {
                    outcome = DrawQueueTelemetry.SubmissionOutcome.REPLAYED;
                    return replay(raced.get(), fingerprint);
                }
                throw new BusinessException(ErrorCode.CONFLICT);
            }
        } catch (DrawQueueRateLimitException limited) {
            outcome = DrawQueueTelemetry.SubmissionOutcome.RATE_LIMITED;
            throw limited;
        } catch (DrawQueueProtectionUnavailableException unavailable) {
            outcome = admissionOutcome(unavailable);
            throw unavailable;
        } finally {
            recordSubmission(outcome, System.nanoTime() - started);
        }
    }

    public DrawQueueView status(String requestKey) {
        long started = System.nanoTime();
        DrawQueueTelemetry.QueryOutcome outcome = DrawQueueTelemetry.QueryOutcome.FAILED;
        try {
            long userId = actors.currentActor().userId();
            DrawQueueTrafficDecision traffic = trafficGuard.checkStatusQuery(userId);
            if (!traffic.allowed()) throw new DrawQueueRateLimitException(Math.max(1, traffic.retryAfterSeconds()));
            DrawQueueView view = queue.findByUserAndKey(userId, requestKey).map(DrawQueueView::from)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            outcome = DrawQueueTelemetry.QueryOutcome.FOUND;
            return view;
        } catch (BusinessException failure) {
            if (failure.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                outcome = DrawQueueTelemetry.QueryOutcome.NOT_FOUND;
            } else if (failure.getErrorCode() == ErrorCode.RATE_LIMITED) {
                outcome = DrawQueueTelemetry.QueryOutcome.RATE_LIMITED;
            }
            throw failure;
        } finally {
            recordStatusQuery(outcome, System.nanoTime() - started);
        }
    }

    /** Kafka 通知和数据库扫描器都可调用；claim token 使过期 worker 无法覆盖新 owner 的状态。 */
    public void process(long queueRequestId) {
        long started = System.nanoTime();
        DrawQueueTelemetry.ProcessingOutcome outcome = DrawQueueTelemetry.ProcessingOutcome.FAILED;
        try {
            outcome = processClaimed(queueRequestId);
        } finally {
            recordProcessing(outcome, System.nanoTime() - started);
        }
    }

    private DrawQueueTelemetry.ProcessingOutcome processClaimed(long queueRequestId) {
        DrawQueueRepository.Entry before = queue.findById(queueRequestId)
                .orElseThrow(() -> new IllegalStateException("Queued draw record not found"));
        if (terminal(before.status())) return DrawQueueTelemetry.ProcessingOutcome.ALREADY_TERMINAL;

        String claimToken = UUID.randomUUID().toString();
        if (!queue.claim(queueRequestId, claimToken, leaseDuration)) {
            return DrawQueueTelemetry.ProcessingOutcome.CLAIMED_ELSEWHERE;
        }

        ScheduledFuture<?> heartbeat = startHeartbeat(queueRequestId, claimToken);
        try {
            DrawQueueRepository.Entry claimed = queue.findById(queueRequestId)
                    .orElseThrow(() -> new IllegalStateException("Claimed draw queue record disappeared"));
            DrawCommand command = new DrawCommand(claimed.campaignId(), claimed.requestKey());

            // 先查权威抽奖记录，修复“开奖事务已提交、队列终态未提交”的崩溃窗口。
            Optional<DrawResult> committed = draws.findQueuedResult(command, claimed.userId());
            if (committed.isPresent()) {
                return queue.complete(queueRequestId, claimToken, committed.get().id())
                        ? DrawQueueTelemetry.ProcessingOutcome.COMPLETED
                        : DrawQueueTelemetry.ProcessingOutcome.CLAIM_LOST;
            }
            if (!queue.recordAttempt(queueRequestId, claimToken, maxAttempts)) {
                return queue.manualReview(queueRequestId, claimToken, fallbackError(claimed.lastErrorCode()))
                        ? DrawQueueTelemetry.ProcessingOutcome.MANUAL_REVIEW
                        : DrawQueueTelemetry.ProcessingOutcome.CLAIM_LOST;
            }

            try {
                DrawResult result = draws.drawQueued(command, claimed.userId());
                return queue.complete(queueRequestId, claimToken, result.id())
                        ? DrawQueueTelemetry.ProcessingOutcome.COMPLETED
                        : DrawQueueTelemetry.ProcessingOutcome.CLAIM_LOST;
            } catch (BusinessException failure) {
                if (isDeterministicRejection(failure.getErrorCode())) {
                    return queue.reject(queueRequestId, claimToken, failure.getErrorCode().code())
                            ? DrawQueueTelemetry.ProcessingOutcome.REJECTED
                            : DrawQueueTelemetry.ProcessingOutcome.CLAIM_LOST;
                }
                return retryOrEscalate(queueRequestId, claimToken, claimed.attempts() + 1,
                        failure.getErrorCode().code());
            } catch (RuntimeException failure) {
                log.warn("draw_queue_processing_failed queueRequestId={} failureType={}",
                        queueRequestId, failure.getClass().getSimpleName());
                return retryOrEscalate(queueRequestId, claimToken, claimed.attempts() + 1,
                        ErrorCode.SERVICE_UNAVAILABLE.code());
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    /** MySQL 扫描补偿 Kafka 通知丢失、消息进 DLT 或 worker 进程中断。 */
    public int processDue() {
        var due = queue.findDueIds(scanBatchSize);
        int attempted = 0;
        for (long id : due) {
            attempted++;
            try {
                process(id);
            } catch (RuntimeException failure) {
                log.warn("draw_queue_recovery_failed queueRequestId={} failureType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
        return attempted;
    }

    private ScheduledFuture<?> startHeartbeat(long id, String claimToken) {
        long periodMillis = Math.max(1000L, leaseDuration.toMillis() / 3);
        Duration period = Duration.ofMillis(periodMillis);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                queue.heartbeat(id, claimToken, leaseDuration);
            } catch (RuntimeException failure) {
                log.warn("draw_queue_heartbeat_failed queueRequestId={} failureType={}",
                        id, failure.getClass().getSimpleName());
            }
        }, period);
        if (future == null) throw new IllegalStateException("Could not schedule draw queue heartbeat");
        return future;
    }

    private DrawQueueTelemetry.ProcessingOutcome retryOrEscalate(long id, String claimToken,
            int attempt, String errorCode) {
        if (attempt >= maxAttempts) {
            return queue.manualReview(id, claimToken, errorCode)
                    ? DrawQueueTelemetry.ProcessingOutcome.MANUAL_REVIEW
                    : DrawQueueTelemetry.ProcessingOutcome.CLAIM_LOST;
        }
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration delay;
        try {
            delay = retryBase.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            delay = retryMax;
        }
        if (delay.compareTo(retryMax) > 0) delay = retryMax;
        return queue.retry(id, claimToken, errorCode, delay)
                ? DrawQueueTelemetry.ProcessingOutcome.RETRY_SCHEDULED
                : DrawQueueTelemetry.ProcessingOutcome.CLAIM_LOST;
    }

    private void recordSubmission(DrawQueueTelemetry.SubmissionOutcome outcome, long elapsedNanos) {
        try {
            telemetry.recordSubmission(outcome, elapsedNanos);
        } catch (RuntimeException failure) {
            log.warn("draw_queue_telemetry_failed operation=submission failureType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void recordStatusQuery(DrawQueueTelemetry.QueryOutcome outcome, long elapsedNanos) {
        try {
            telemetry.recordStatusQuery(outcome, elapsedNanos);
        } catch (RuntimeException failure) {
            log.warn("draw_queue_telemetry_failed operation=status_query failureType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void recordProcessing(DrawQueueTelemetry.ProcessingOutcome outcome, long elapsedNanos) {
        try {
            telemetry.recordProcessing(outcome, elapsedNanos);
        } catch (RuntimeException failure) {
            log.warn("draw_queue_telemetry_failed operation=processing failureType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private static DrawQueueTelemetry.SubmissionOutcome admissionOutcome(
            DrawQueueProtectionUnavailableException unavailable) {
        return switch (unavailable.reason()) {
            case MYSQL_BACKLOG_LIMIT, MYSQL_OLDEST_REQUEST_AGE_LIMIT,
                    MYSQL_CONNECTION_POOL_PRESSURE -> DrawQueueTelemetry.SubmissionOutcome.CAPACITY_REJECTED;
            case REDIS_UNAVAILABLE, REDIS_INVALID_RESPONSE ->
                    DrawQueueTelemetry.SubmissionOutcome.PROTECTION_UNAVAILABLE;
        };
    }

    private static boolean isDeterministicRejection(ErrorCode code) {
        return code != ErrorCode.SERVICE_UNAVAILABLE && code != ErrorCode.INTERNAL_ERROR;
    }

    private static String fallbackError(String code) {
        return code == null ? ErrorCode.SERVICE_UNAVAILABLE.code() : code;
    }

    private static boolean terminal(DrawQueueRepository.Status status) {
        return status == DrawQueueRepository.Status.COMPLETED
                || status == DrawQueueRepository.Status.REJECTED
                || status == DrawQueueRepository.Status.MANUAL_REVIEW;
    }

    private static DrawQueueView replay(DrawQueueRepository.Entry entry, byte[] fingerprint) {
        if (!MessageDigest.isEqual(entry.fingerprint(), fingerprint)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return DrawQueueView.from(entry);
    }

    private static byte[] fingerprint(long campaignId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("QUEUED_DRAW|" + campaignId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
