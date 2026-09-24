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

    public FulfillmentRetryService(FulfillmentRepository fulfillments, FulfillmentExecutor executor,
            FulfillmentRetryPolicy policy, Clock clock) {
        this.fulfillments = fulfillments;
        this.executor = executor;
        this.policy = policy;
        this.clock = clock;
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
        if (claim.attemptCount() > policy.maxAttempts()) {
            fulfillments.moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                    "ATTEMPT_LIMIT_AFTER_LEASE_EXPIRY", clock.instant());
            return;
        }
        try {
            executor.execute(Long.toString(claim.drawId()), claim.payload());
            if (!fulfillments.markSucceeded(claim.id(), claim.leaseToken(), clock.instant())) {
                log.warn("fulfillment outcome fenced drawId={}", claim.drawId());
            }
        } catch (FulfillmentExecutionException failure) {
            Instant failedAt = clock.instant();
            var decision = policy.afterFailure(claim.attemptCount(), failure.permanent(), failedAt);
            if (decision.nextAttemptAt() == null) {
                fulfillments.moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                        failure.errorCode(), failedAt);
                log.warn("fulfillment requires manual review drawId={} reason={}",
                        claim.drawId(), failure.errorCode());
            } else {
                fulfillments.scheduleRetry(claim.id(), claim.leaseToken(), decision.nextAttemptAt(),
                        failure.errorCode(), failedAt);
                log.warn("fulfillment retry scheduled drawId={} reason={} nextAttemptAt={}",
                        claim.drawId(), failure.errorCode(), decision.nextAttemptAt());
            }
        } catch (RuntimeException unexpected) {
            Instant failedAt = clock.instant();
            var decision = policy.afterFailure(claim.attemptCount(), false, failedAt);
            if (decision.nextAttemptAt() == null) {
                fulfillments.moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                        "UNEXPECTED_FAILURE", failedAt);
                log.warn("fulfillment requires manual review drawId={} reason=UNEXPECTED_FAILURE",
                        claim.drawId());
            } else {
                fulfillments.scheduleRetry(claim.id(), claim.leaseToken(), decision.nextAttemptAt(),
                        "UNEXPECTED_FAILURE", failedAt);
                log.warn("fulfillment retry scheduled drawId={} reason=UNEXPECTED_FAILURE",
                        claim.drawId());
            }
        }
    }
}
