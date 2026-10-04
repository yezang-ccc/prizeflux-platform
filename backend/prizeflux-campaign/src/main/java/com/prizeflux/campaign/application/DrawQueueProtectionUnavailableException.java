package com.prizeflux.campaign.application;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

/** Internal reason for a fail-closed queue admission response; the public error contract stays generic. */
public final class DrawQueueProtectionUnavailableException extends BusinessException {
    public enum Reason {
        REDIS_UNAVAILABLE,
        REDIS_INVALID_RESPONSE,
        MYSQL_BACKLOG_LIMIT,
        MYSQL_OLDEST_REQUEST_AGE_LIMIT,
        MYSQL_CONNECTION_POOL_PRESSURE
    }

    private final Reason reason;

    public DrawQueueProtectionUnavailableException(Reason reason) {
        super(ErrorCode.SERVICE_UNAVAILABLE);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }
}
