package com.prizeflux.ai.harness.session;

import java.time.Duration;
import java.util.Objects;

/** Explicit resource bounds for this process-local session implementation. */
public record SessionPolicy(int maxSessionsPerOwner, int maxSessionsTotal, int maxMessagesPerSession,
                            int maxCharactersPerSession, Duration idleTtl) {
    public SessionPolicy {
        Objects.requireNonNull(idleTtl, "idleTtl");
        if (maxSessionsPerOwner < 1 || maxSessionsTotal < 1 || maxMessagesPerSession < 1 || maxCharactersPerSession < 1
                || idleTtl.isZero() || idleTtl.isNegative()) {
            throw new IllegalArgumentException("invalid session policy");
        }
    }

    public static SessionPolicy defaults() {
        return new SessionPolicy(10, 1_000, 100, 100_000, Duration.ofMinutes(30));
    }
}
