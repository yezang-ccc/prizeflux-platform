package com.prizeflux.ai.harness.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prizeflux.ai.harness.application.AgentRunner;
import com.prizeflux.ai.harness.application.RunLimits;
import com.prizeflux.ai.harness.application.RunStatus;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AuthorizedToolExecutorTest {
    private static final Duration BUDGET = Duration.ofSeconds(2);
    private static final ArgumentRule ID = new ArgumentRule(true, Pattern.compile("[1-9][0-9]*"), 18);

    @Test
    void authorizedCallReceivesServerActor() {
        Actor serverActor = new Actor(42, Role.OPERATOR);
        AtomicReference<Actor> seen = new AtomicReference<>();
        AuthorizedToolExecutor executor = new AuthorizedToolExecutor(serverActor, List.of(
                summary((actor, args, remaining) -> {
                    seen.set(actor);
                    return ToolResult.ok("campaign " + args.get("campaignId"));
                })));
        ToolResult result = executor.execute(call("campaign.summary", Map.of("campaignId", "1005")), BUDGET);
        assertEquals(true, result.success());
        assertEquals("campaign 1005", result.content());
        assertEquals(serverActor, seen.get());
    }

    @Test
    void unknownToolAndRoleMismatchNeverInvokeOperation() {
        AtomicInteger calls = new AtomicInteger();
        AuthorizedToolExecutor executor = new AuthorizedToolExecutor(new Actor(7, Role.USER), List.of(
                summary((actor, args, remaining) -> {
                    calls.incrementAndGet();
                    return ToolResult.ok("private");
                })));
        assertDenied(executor, "UNKNOWN_TOOL", call("campaign.write", Map.of("campaignId", "1005")));
        assertDenied(executor, "TOOL_FORBIDDEN", call("campaign.summary", Map.of("campaignId", "1005")));
        assertEquals(0, calls.get());
    }

    @Test
    void modelCannotSupplyIdentityOrExtraParameters() {
        AtomicInteger calls = new AtomicInteger();
        AuthorizedToolExecutor executor = countingExecutor(calls);
        assertDenied(executor, "INVALID_ARGUMENTS", call("campaign.summary",
                Map.of("campaignId", "1005", "userId", "999")));
        assertDenied(executor, "INVALID_ARGUMENTS", call("campaign.summary",
                Map.of("campaignId", "1005", "role", "OPERATOR")));
        assertDenied(executor, "INVALID_ARGUMENTS", call("campaign.summary",
                Map.of("campaignId", "1005", "sql", "DROP TABLE campaign")));
        assertEquals(0, calls.get());
    }

    @Test
    void missingMalformedOrOversizedArgumentsAreRejected() {
        AtomicInteger calls = new AtomicInteger();
        AuthorizedToolExecutor executor = countingExecutor(calls);
        assertDenied(executor, "INVALID_ARGUMENTS", call("campaign.summary", Map.of()));
        assertDenied(executor, "INVALID_ARGUMENTS", call("campaign.summary", Map.of("campaignId", "1 OR 1=1")));
        assertDenied(executor, "INVALID_ARGUMENTS", call("campaign.summary",
                Map.of("campaignId", "12345678901234567890")));
        assertEquals(0, calls.get());
    }

    @Test
    void deadlineAndDuplicateRegistrationsFailClosed() {
        AtomicInteger calls = new AtomicInteger();
        AuthorizedToolExecutor executor = countingExecutor(calls);
        ToolResult result = executor.execute(call("campaign.summary", Map.of("campaignId", "1005")),
                Duration.ZERO);
        assertEquals("TIME_BUDGET_EXCEEDED", result.content());
        assertEquals(0, calls.get());
        ToolDefinition definition = summary((actor, args, remaining) -> ToolResult.ok("ok"));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizedToolExecutor(new Actor(1, Role.OPERATOR), List.of(definition, definition)));
    }

    @Test
    void cannotRegisterModelControlledIdentityParameter() {
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("campaign.summary", Role.USER,
                Map.of("userId", ID), (actor, args, remaining) -> ToolResult.ok("ok")));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("campaign.summary", Role.USER,
                Map.of("role", ID), (actor, args, remaining) -> ToolResult.ok("ok")));
    }

    @Test
    void exceptionsAndReportedErrorsAreSanitized() {
        AuthorizedToolExecutor throwing = new AuthorizedToolExecutor(new Actor(1, Role.OPERATOR), List.of(
                summary((actor, args, remaining) -> { throw new IllegalStateException("password=secret"); })));
        assertDenied(throwing, "TOOL_UNAVAILABLE", call("campaign.summary", Map.of("campaignId", "1005")));

        AuthorizedToolExecutor failing = new AuthorizedToolExecutor(new Actor(1, Role.OPERATOR), List.of(
                summary((actor, args, remaining) -> ToolResult.error("internal token=secret"))));
        assertDenied(failing, "TOOL_REPORTED_ERROR", call("campaign.summary", Map.of("campaignId", "1005")));
    }

    @Test
    void oversizedResultIsNotPassedBackToModel() {
        AuthorizedToolExecutor executor = new AuthorizedToolExecutor(new Actor(1, Role.OPERATOR), List.of(
                summary((actor, args, remaining) -> ToolResult.ok("x".repeat(4097)))));
        assertDenied(executor, "TOOL_RESULT_TOO_LARGE", call("campaign.summary", Map.of("campaignId", "1005")));
    }

    @Test
    void runnerCanObserveDeniedToolWithoutExecutingIt() {
        AtomicInteger calls = new AtomicInteger();
        AuthorizedToolExecutor executor = countingExecutor(calls);
        AtomicInteger turns = new AtomicInteger();
        AgentRunner runner = new AgentRunner((messages, remaining) -> {
            if (turns.getAndIncrement() == 0) {
                return call("campaign.write", Map.of());
            }
            assertEquals("UNKNOWN_TOOL", messages.get(2).content());
            return new ModelAction.FinalAnswer("I cannot do that");
        }, executor, Clock.systemUTC());
        var result = runner.run("change inventory", new RunLimits(3, 1, Duration.ofSeconds(3)));
        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals(0, calls.get());
    }

    private AuthorizedToolExecutor countingExecutor(AtomicInteger calls) {
        return new AuthorizedToolExecutor(new Actor(42, Role.OPERATOR), List.of(
                summary((actor, args, remaining) -> {
                    calls.incrementAndGet();
                    return ToolResult.ok("ok");
                })));
    }

    private ToolDefinition summary(ReadOnlyOperation operation) {
        return new ToolDefinition("campaign.summary", Role.OPERATOR, Map.of("campaignId", ID), operation);
    }

    private ToolCall call(String name, Map<String, String> args) {
        return new ToolCall(name, args);
    }

    private void assertDenied(AuthorizedToolExecutor executor, String code, ToolCall call) {
        ToolResult result = executor.execute(call, BUDGET);
        assertEquals(false, result.success());
        assertEquals(code, result.content());
    }
}
