package com.prizeflux.ai.harness.safety;

import java.util.Objects;

/** Constructed by the host from its verified security context, never by the model. */
public record Actor(long userId, Role role) {
    public Actor {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Objects.requireNonNull(role, "role");
    }
}
