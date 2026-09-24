package com.prizeflux.ai.harness.context;

/** The newest complete user interaction cannot fit without lossy truncation. */
public final class ContextLimitExceededException extends RuntimeException {
    public ContextLimitExceededException() {
        super("current interaction exceeds context budget");
    }
}
