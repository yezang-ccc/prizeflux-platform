package com.prizeflux.ai.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRunnerTest {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
    private static final RunLimits LIMITS = new RunLimits(5, 3, Duration.ofSeconds(30));
    private static final ToolCall READ = new ToolCall("campaign.read", Map.of("campaignId", "1005"));

    @Test
    void directAnswerNeverCallsTool() {
        AtomicInteger toolCalls = new AtomicInteger();
        AgentRunner runner = new AgentRunner((messages, remaining) -> new ModelAction.FinalAnswer("ready"),
                (call, remaining) -> { toolCalls.incrementAndGet(); return ToolResult.ok("unexpected"); }, FIXED);
        RunResult result = runner.run("hello", LIMITS);
        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals("ready", result.answer());
        assertEquals(1, result.modelTurns());
        assertEquals(0, toolCalls.get());
        assertEquals(List.of(AgentMessage.Kind.USER, AgentMessage.Kind.ASSISTANT_ANSWER),
                result.transcript().stream().map(AgentMessage::kind).toList());
    }

    @Test
    void toolObservationReturnsToModelInOrder() {
        List<List<AgentMessage>> modelViews = new ArrayList<>();
        Queue<ModelAction> script = new ArrayDeque<>(List.of(READ, new ModelAction.FinalAnswer("campaign 1005")));
        ModelPort model = (messages, remaining) -> {
            modelViews.add(messages);
            return script.remove();
        };
        AgentRunner runner = new AgentRunner(model, (call, remaining) -> ToolResult.ok("active"), FIXED);
        RunResult result = runner.run("status?", LIMITS);
        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals(2, result.modelTurns());
        assertEquals(1, result.toolCalls());
        assertEquals(1, modelViews.get(0).size());
        assertEquals(3, modelViews.get(1).size());
        assertEquals("active", modelViews.get(1).get(2).content());
        assertEquals(List.of(AgentMessage.Kind.USER, AgentMessage.Kind.ASSISTANT_TOOL_CALL,
                        AgentMessage.Kind.TOOL_OBSERVATION, AgentMessage.Kind.ASSISTANT_ANSWER),
                result.transcript().stream().map(AgentMessage::kind).toList());
    }

    @Test
    void multipleToolsAndErrorObservationCanStillLeadToAnswer() {
        Queue<ModelAction> script = new ArrayDeque<>(List.of(READ, READ,
                new ModelAction.FinalAnswer("unavailable")));
        AtomicInteger calls = new AtomicInteger();
        AgentRunner runner = new AgentRunner((messages, remaining) -> script.remove(),
                (call, remaining) -> calls.incrementAndGet() == 1
                        ? ToolResult.ok("first") : ToolResult.error("denied"), FIXED);
        RunResult result = runner.run("status?", LIMITS);
        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals(2, result.toolCalls());
        assertEquals(false, result.transcript().get(4).toolSuccess());
    }

    @Test
    void toolLimitStopsBeforeExtraExecution() {
        AtomicInteger calls = new AtomicInteger();
        AgentRunner runner = new AgentRunner((messages, remaining) -> READ,
                (call, remaining) -> { calls.incrementAndGet(); return ToolResult.ok("ok"); }, FIXED);
        RunResult result = runner.run("status?", new RunLimits(5, 1, Duration.ofSeconds(30)));
        assertEquals(RunStatus.TOOL_CALL_LIMIT, result.status());
        assertEquals(1, calls.get());
        assertEquals(1, result.toolCalls());
        assertNull(result.answer());
    }

    @Test
    void modelTurnLimitStopsLoop() {
        AgentRunner runner = new AgentRunner((messages, remaining) -> READ,
                (call, remaining) -> ToolResult.ok("ok"), FIXED);
        RunResult result = runner.run("status?", new RunLimits(2, 3, Duration.ofSeconds(30)));
        assertEquals(RunStatus.MODEL_TURN_LIMIT, result.status());
        assertEquals(2, result.modelTurns());
        assertEquals(2, result.toolCalls());
    }

    @Test
    void nullActionAndAdapterFailuresAreSeparateStatuses() {
        AgentRunner invalid = new AgentRunner((messages, remaining) -> null,
                (call, remaining) -> ToolResult.ok("ok"), FIXED);
        assertEquals(RunStatus.INVALID_MODEL_ACTION, invalid.run("status?", LIMITS).status());

        AgentRunner badModel = new AgentRunner((messages, remaining) -> { throw new IllegalStateException("secret"); },
                (call, remaining) -> ToolResult.ok("ok"), FIXED);
        assertEquals(RunStatus.MODEL_FAILURE, badModel.run("status?", LIMITS).status());

        AgentRunner badTool = new AgentRunner((messages, remaining) -> READ,
                (call, remaining) -> { throw new IllegalStateException("secret"); }, FIXED);
        RunResult result = badTool.run("status?", LIMITS);
        assertEquals(RunStatus.TOOL_FAILURE, result.status());
        assertNull(result.answer());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void deadlineAfterModelPreventsToolCall() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        AgentRunner runner = new AgentRunner((messages, remaining) -> {
            clock.advance(Duration.ofSeconds(2));
            return READ;
        }, (call, remaining) -> { calls.incrementAndGet(); return ToolResult.ok("ok"); }, clock);
        RunResult result = runner.run("status?", new RunLimits(3, 3, Duration.ofSeconds(1)));
        assertEquals(RunStatus.DEADLINE_EXCEEDED, result.status());
        assertEquals(0, calls.get());
    }

    @Test
    void repeatableFakeScriptProducesIdenticalRun() {
        RunResult first = scriptedRun();
        RunResult second = scriptedRun();
        assertEquals(first, second);
    }

    @Test
    void rejectsBlankPrompt() {
        AgentRunner runner = new AgentRunner((messages, remaining) -> new ModelAction.FinalAnswer("yes"),
                (call, remaining) -> ToolResult.ok("ok"), FIXED);
        assertThrows(IllegalArgumentException.class, () -> runner.run(" ", LIMITS));
    }

    private RunResult scriptedRun() {
        Queue<ModelAction> script = new ArrayDeque<>(List.of(READ, new ModelAction.FinalAnswer("done")));
        return new AgentRunner((messages, remaining) -> script.remove(),
                (call, remaining) -> ToolResult.ok("active"), FIXED).run("status?", LIMITS);
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-09-24T00:00:00Z");

        void advance(Duration amount) { current = current.plus(amount); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
