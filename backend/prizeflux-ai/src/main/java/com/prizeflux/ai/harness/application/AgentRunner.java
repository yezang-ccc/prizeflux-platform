package com.prizeflux.ai.harness.application;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Small, framework-free control loop; it cannot execute a tool without its injected port. */
public final class AgentRunner {
    private final ModelPort model;
    private final ToolExecutor tools;
    private final Clock clock;

    public AgentRunner(ModelPort model, ToolExecutor tools, Clock clock) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RunResult run(String prompt, RunLimits limits) {
        Objects.requireNonNull(prompt, "prompt");
        return runWithTranscript(List.of(AgentMessage.user(prompt)), limits);
    }

    /** Runs from a bounded prior transcript whose last event is the current user request. */
    public RunResult runWithTranscript(List<AgentMessage> initialTranscript, RunLimits limits) {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(initialTranscript, "initialTranscript");
        List<AgentMessage> transcript = new ArrayList<>(initialTranscript);
        if (transcript.isEmpty() || transcript.get(transcript.size() - 1).kind() != AgentMessage.Kind.USER) {
            throw new IllegalArgumentException("initial transcript must end with the current user request");
        }
        Instant deadline = clock.instant().plus(limits.maxDuration());
        int modelTurns = 0;
        int toolCalls = 0;

        while (true) {
            if (expired(deadline)) {
                return result(RunStatus.DEADLINE_EXCEEDED, transcript, modelTurns, toolCalls);
            }
            if (modelTurns >= limits.maxModelTurns()) {
                return result(RunStatus.MODEL_TURN_LIMIT, transcript, modelTurns, toolCalls);
            }

            ModelAction action;
            modelTurns++;
            try {
                action = model.next(List.copyOf(transcript), remaining(deadline));
            } catch (RuntimeException exception) {
                return result(RunStatus.MODEL_FAILURE, transcript, modelTurns, toolCalls);
            }
            if (expired(deadline)) {
                return result(RunStatus.DEADLINE_EXCEEDED, transcript, modelTurns, toolCalls);
            }
            if (action instanceof ModelAction.FinalAnswer answer) {
                transcript.add(AgentMessage.answer(answer.text()));
                return new RunResult(RunStatus.COMPLETED, answer.text(), transcript, modelTurns, toolCalls);
            }
            if (!(action instanceof ToolCall call)) {
                return result(RunStatus.INVALID_MODEL_ACTION, transcript, modelTurns, toolCalls);
            }

            transcript.add(AgentMessage.toolCall(call));
            if (toolCalls >= limits.maxToolCalls()) {
                return result(RunStatus.TOOL_CALL_LIMIT, transcript, modelTurns, toolCalls);
            }
            if (expired(deadline)) {
                return result(RunStatus.DEADLINE_EXCEEDED, transcript, modelTurns, toolCalls);
            }

            ToolResult observation;
            toolCalls++;
            try {
                observation = tools.execute(call, remaining(deadline));
            } catch (RuntimeException exception) {
                return result(RunStatus.TOOL_FAILURE, transcript, modelTurns, toolCalls);
            }
            if (observation == null) {
                return result(RunStatus.TOOL_FAILURE, transcript, modelTurns, toolCalls);
            }
            transcript.add(AgentMessage.toolObservation(call, observation));
            if (expired(deadline)) {
                return result(RunStatus.DEADLINE_EXCEEDED, transcript, modelTurns, toolCalls);
            }
        }
    }

    private boolean expired(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    private Duration remaining(Instant deadline) {
        return Duration.between(clock.instant(), deadline);
    }

    private RunResult result(RunStatus status, List<AgentMessage> transcript,
                             int modelTurns, int toolCalls) {
        return new RunResult(status, null, transcript, modelTurns, toolCalls);
    }
}
