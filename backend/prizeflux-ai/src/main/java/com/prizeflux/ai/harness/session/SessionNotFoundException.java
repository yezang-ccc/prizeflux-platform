package com.prizeflux.ai.harness.session;

/** Intentionally does not distinguish an absent ID, expiry, or another owner. */
public final class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException() {
        super("session not found");
    }
}
