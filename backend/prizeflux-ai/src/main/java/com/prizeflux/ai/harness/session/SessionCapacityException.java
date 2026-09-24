package com.prizeflux.ai.harness.session;

/** A documented per-owner or per-session limit was reached. */
public final class SessionCapacityException extends RuntimeException {
    public SessionCapacityException() {
        super("session capacity exceeded");
    }
}
