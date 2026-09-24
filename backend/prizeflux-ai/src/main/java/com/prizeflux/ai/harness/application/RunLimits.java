package com.prizeflux.ai.harness.application;

import java.time.Duration;
import java.util.Objects;

public record RunLimits(int maxModelTurns, int maxToolCalls, Duration maxDuration) {
    public RunLimits {
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxModelTurns < 1 || maxModelTurns > 100
                || maxToolCalls < 0 || maxToolCalls > 100
                || maxDuration.isZero() || maxDuration.isNegative()
                || maxDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("invalid run limits");
        }
    }
}
