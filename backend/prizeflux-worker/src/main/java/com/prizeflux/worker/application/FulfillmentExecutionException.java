package com.prizeflux.worker.application;

/** A sanitized, classified executor failure; raw downstream messages are never persisted or logged. */
public final class FulfillmentExecutionException extends RuntimeException {
    private final boolean permanent;
    private final String errorCode;

    public FulfillmentExecutionException(String errorCode, boolean permanent) {
        super("Fulfillment execution failed");
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid fulfillment error code");
        }
        this.errorCode = errorCode;
        this.permanent = permanent;
    }

    public boolean permanent() { return permanent; }
    public String errorCode() { return errorCode; }
}
