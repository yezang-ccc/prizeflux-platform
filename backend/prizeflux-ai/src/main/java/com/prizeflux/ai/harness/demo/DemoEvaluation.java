package com.prizeflux.ai.harness.demo;

import com.prizeflux.ai.harness.application.AgentRunner;
import com.prizeflux.ai.harness.application.RunLimits;
import com.prizeflux.ai.harness.application.RunResult;
import com.prizeflux.ai.harness.application.RunStatus;
import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.ArgumentRule;
import com.prizeflux.ai.harness.safety.AuthorizedToolExecutor;
import com.prizeflux.ai.harness.safety.Role;
import com.prizeflux.ai.harness.safety.ToolDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Fixed offline cases; these are harness behavior checks, not model-quality metrics. */
public final class DemoEvaluation {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
    private static final ToolCall READ = new ToolCall("campaign.summary", Map.of("campaignId", "1005"));

    private DemoEvaluation() { }

    public static List<CaseResult> run() {
        return List.of(normalRead(), forbiddenWrite(), toolLimit());
    }

    private static CaseResult normalRead() {
        AtomicInteger executed = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(List.of(
                messages -> READ,
                messages -> new ModelAction.FinalAnswer("SIMULATED result: "
                        + messages.get(messages.size() - 1).content())));
        RunResult result = new AgentRunner(model, fakeTools(executed), CLOCK).run("Read campaign 1005",
                new RunLimits(3, 2, Duration.ofSeconds(5)));
        boolean passed = result.status() == RunStatus.COMPLETED && result.toolCalls() == 1
                && executed.get() == 1 && result.answer().contains("SIMULATED")
                && result.transcript().get(2).kind() == AgentMessage.Kind.TOOL_OBSERVATION;
        return new CaseResult("authorized-read", passed, result, executed.get());
    }

    private static CaseResult forbiddenWrite() {
        AtomicInteger executed = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(List.of(
                messages -> new ToolCall("campaign.write", Map.of()),
                messages -> new ModelAction.FinalAnswer("SIMULATED refusal")));
        RunResult result = new AgentRunner(model, fakeTools(executed), CLOCK).run("Change inventory",
                new RunLimits(3, 2, Duration.ofSeconds(5)));
        boolean passed = result.status() == RunStatus.COMPLETED && executed.get() == 0
                && result.transcript().get(2).content().equals("UNKNOWN_TOOL");
        return new CaseResult("forbidden-write", passed, result, executed.get());
    }

    private static CaseResult toolLimit() {
        AtomicInteger executed = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(List.of(messages -> READ, messages -> READ));
        RunResult result = new AgentRunner(model, fakeTools(executed), CLOCK).run("Read repeatedly",
                new RunLimits(3, 1, Duration.ofSeconds(5)));
        boolean passed = result.status() == RunStatus.TOOL_CALL_LIMIT && executed.get() == 1
                && result.toolCalls() == 1;
        return new CaseResult("tool-limit", passed, result, executed.get());
    }

    private static AuthorizedToolExecutor fakeTools(AtomicInteger executed) {
        ToolDefinition summary = new ToolDefinition("campaign.summary", Role.OPERATOR,
                Map.of("campaignId", new ArgumentRule(true, Pattern.compile("[1-9][0-9]*"), 18)),
                (actor, arguments, remaining) -> {
                    executed.incrementAndGet();
                    return ToolResult.ok("{\"id\":1005,\"name\":\"SIMULATED Autumn\","
                            + "\"status\":\"PUBLISHED\"}");
                });
        return new AuthorizedToolExecutor(new Actor(1, Role.OPERATOR), List.of(summary));
    }

    public record CaseResult(String name, boolean passed, RunResult result, int executedOperations) { }
}
