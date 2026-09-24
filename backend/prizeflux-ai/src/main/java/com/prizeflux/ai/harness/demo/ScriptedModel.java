package com.prizeflux.ai.harness.demo;

import com.prizeflux.ai.harness.application.ModelPort;
import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Deterministic one-run fake; no provider SDK or network request. */
public final class ScriptedModel implements ModelPort {
    private final List<Function<List<AgentMessage>, ModelAction>> steps;
    private int nextStep;

    public ScriptedModel(List<Function<List<AgentMessage>, ModelAction>> steps) {
        this.steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("script is empty");
        }
    }

    @Override
    public ModelAction next(List<AgentMessage> transcript, Duration remaining) {
        Objects.requireNonNull(transcript, "transcript");
        if (nextStep >= steps.size()) {
            return null;
        }
        return steps.get(nextStep++).apply(transcript);
    }
}
