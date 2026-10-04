package com.prizeflux.campaign.application;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

/** Rate limit failure carrying a safe Retry-After hint for the HTTP boundary. */
public final class DrawQueueRateLimitException extends BusinessException {
    private final long retryAfterSeconds;

    public DrawQueueRateLimitException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED);
        if (retryAfterSeconds < 1) throw new IllegalArgumentException("Retry delay must be positive");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
