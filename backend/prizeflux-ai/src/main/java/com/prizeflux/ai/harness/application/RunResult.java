package com.prizeflux.ai.harness.application;

import com.prizeflux.ai.harness.model.AgentMessage;
import java.util.List;
import java.util.Objects;

/** Only COMPLETED has an answer; a failure never masquerades as model output. */
public record RunResult(RunStatus status, String answer, List<AgentMessage> transcript,
                        int modelTurns, int toolCalls) {
    public RunResult {
        Objects.requireNonNull(status, "status");
        transcript = List.copyOf(transcript);
        if (modelTurns < 0 || toolCalls < 0
                || (status == RunStatus.COMPLETED && (answer == null || answer.isBlank()))
                || (status != RunStatus.COMPLETED && answer != null)) {
            throw new IllegalArgumentException("invalid run result");
        }
    }
}
