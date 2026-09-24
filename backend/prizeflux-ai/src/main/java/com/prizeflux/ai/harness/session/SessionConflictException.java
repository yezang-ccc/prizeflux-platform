package com.prizeflux.ai.harness.session;

/** Optimistic concurrency conflict: a newer turn committed after the caller read. */
public final class SessionConflictException extends RuntimeException {
    public SessionConflictException() {
        super("session version conflict");
    }
}
