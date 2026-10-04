package com.prizeflux.worker.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Claims due work durably, runs the side effect outside a DB transaction, then records a fenced outcome. */
@Service
@ConditionalOnProperty(name = "prizeflux.worker.retry.enabled", havingValue = "true")
public class FulfillmentRetryService {
    private static final Logger log = LoggerFactory.getLogger(FulfillmentRetryService.class);
    private static final int CLAIM_LIMIT = 16;
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final FulfillmentRepository fulfillments;
    private final FulfillmentExecutor executor;
    private final FulfillmentRetryPolicy policy;
    private final Clock clock;
    private final FulfillmentTelemetry telemetry;

    public FulfillmentRetryService(FulfillmentRepository fulfillments, FulfillmentExecutor executor,
            FulfillmentRetryPolicy policy, Clock clock, FulfillmentTelemetry telemetry) {
        this.fulfillments = fulfillments;
        this.executor = executor;
        this.policy = policy;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    @Scheduled(fixedDelayString = "${prizeflux.worker.retry.poll-ms:500}")
    public void processDue() {
        Instant now = clock.instant();
        String leaseToken = UUID.randomUUID().toString();
        var claims = fulfillments.claimDue(now, now.plus(LEASE), leaseToken, CLAIM_LIMIT);
        for (FulfillmentClaim claim : claims) {
            executeClaim(claim);
        }
    }

    private void executeClaim(FulfillmentClaim claim) {
        long startedAt = System.nanoTime();
        if (claim.attemptCount() > policy.maxAttempts()) {
            boolean moved = fulfillments.moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                    "ATTEMPT_LIMIT_AFTER_LEASE_EXPIRY", clock.instant());
            recordExecution(moved ? FulfillmentTelemetry.ExecutionOutcome.MANUAL_REVIEW
                    : FulfillmentTelemetry.ExecutionOutcome.CLAIM_LOST, startedAt);
            return;
        }
        try {
            executor.execute(Long.toString(claim.drawId()), claim.payload());
            boolean marked = fulfillments.markSucceeded(claim.id(), claim.leaseToken(), clock.instant());
            if (!marked) {
                log.warn("fulfillment outcome fenced drawId={}", claim.drawId());
            }
            recordExecution(marked ? FulfillmentTelemetry.ExecutionOutcome.SUCCEEDED
                    : FulfillmentTelemetry.ExecutionOutcome.CLAIM_LOST, startedAt);
        } catch (FulfillmentExecutionException failure) {
            Instant failedAt = clock.instant();
            var decision = policy.afterFailure(claim.attemptCount(), failure.permanent(), failedAt);
            if (decision.nextAttemptAt() == null) {
                boolean moved = fulfillments.moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                        failure.errorCode(), failedAt);
                log.warn("fulfillment requires manual review drawId={} reason={}",
                        claim.drawId(), failure.errorCode());
                recordExecution(moved ? FulfillmentTelemetry.ExecutionOutcome.MANUAL_REVIEW
                        : FulfillmentTelemetry.ExecutionOutcome.CLAIM_LOST, startedAt);
            } else {
                boolean scheduled = fulfillments.scheduleRetry(claim.id(), claim.leaseToken(), decision.nextAttemptAt(),
                        failure.errorCode(), failedAt);
                log.warn("fulfillment retry scheduled drawId={} reason={} nextAttemptAt={}",
                        claim.drawId(), failure.errorCode(), decision.nextAttemptAt());
                recordExecution(scheduled ? FulfillmentTelemetry.ExecutionOutcome.RETRY_SCHEDULED
                        : FulfillmentTelemetry.ExecutionOutcome.CLAIM_LOST, startedAt);
            }
        } catch (RuntimeException unexpected) {
            Instant failedAt = clock.instant();
            var decision = policy.afterFailure(claim.attemptCount(), false, failedAt);
            if (decision.nextAttemptAt() == null) {
                boolean moved = fulfillments.moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                        "UNEXPECTED_FAILURE", failedAt);
                log.warn("fulfillment requires manual review drawId={} reason=UNEXPECTED_FAILURE",
                        claim.drawId());
                recordExecution(moved ? FulfillmentTelemetry.ExecutionOutcome.MANUAL_REVIEW
                        : FulfillmentTelemetry.ExecutionOutcome.CLAIM_LOST, startedAt);
            } else {
                boolean scheduled = fulfillments.scheduleRetry(claim.id(), claim.leaseToken(), decision.nextAttemptAt(),
                        "UNEXPECTED_FAILURE", failedAt);
                log.warn("fulfillment retry scheduled drawId={} reason=UNEXPECTED_FAILURE",
                        claim.drawId());
                recordExecution(scheduled ? FulfillmentTelemetry.ExecutionOutcome.RETRY_SCHEDULED
                        : FulfillmentTelemetry.ExecutionOutcome.CLAIM_LOST, startedAt);
            }
        }
    }

    private void recordExecution(FulfillmentTelemetry.ExecutionOutcome outcome, long startedAt) {
        try {
            telemetry.recordExecution(outcome, Math.max(0L, System.nanoTime() - startedAt));
        } catch (RuntimeException failure) {
            log.warn("worker_execution_telemetry_failed failureType={}", failure.getClass().getSimpleName());
        }
    }
}
