package com.prizeflux.ai.harness.safety;

import com.prizeflux.ai.harness.application.ToolExecutor;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Default-deny boundary between untrusted model actions and application tools. */
public final class AuthorizedToolExecutor implements ToolExecutor {
    private static final int MAX_RESULT_CHARS = 4096;
    private final Actor actor;
    private final Map<String, ToolDefinition> definitions;

    public AuthorizedToolExecutor(Actor actor, Collection<ToolDefinition> definitions) {
        this.actor = Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(definitions, "definitions");
        Map<String, ToolDefinition> registered = new HashMap<>();
        for (ToolDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (registered.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("duplicate tool name");
            }
        }
        this.definitions = Map.copyOf(registered);
    }

    @Override
    public ToolResult execute(ToolCall call, Duration remaining) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(remaining, "remaining");
        if (remaining.isZero() || remaining.isNegative()) {
            return ToolResult.error("TIME_BUDGET_EXCEEDED");
        }
        ToolDefinition definition = definitions.get(call.name());
        if (definition == null) {
            return ToolResult.error("UNKNOWN_TOOL");
        }
        if (!actor.role().allows(definition.minimumRole())) {
            return ToolResult.error("TOOL_FORBIDDEN");
        }
        if (!validArguments(call.arguments(), definition.arguments())) {
            return ToolResult.error("INVALID_ARGUMENTS");
        }
        try {
            ToolResult result = definition.operation().execute(actor, call.arguments(), remaining);
            if (result == null) {
                return ToolResult.error("TOOL_UNAVAILABLE");
            }
            if (!result.success()) {
                return ToolResult.error("TOOL_REPORTED_ERROR");
            }
            if (result.content().length() > MAX_RESULT_CHARS) {
                return ToolResult.error("TOOL_RESULT_TOO_LARGE");
            }
            return result;
        } catch (RuntimeException exception) {
            return ToolResult.error("TOOL_UNAVAILABLE");
        }
    }

    private boolean validArguments(Map<String, String> supplied, Map<String, ArgumentRule> rules) {
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            ArgumentRule rule = rules.get(entry.getKey());
            if (rule == null || !rule.accepts(entry.getValue())) {
                return false;
            }
        }
        for (Map.Entry<String, ArgumentRule> entry : rules.entrySet()) {
            if (entry.getValue().required() && !supplied.containsKey(entry.getKey())) {
                return false;
            }
        }
        return true;
    }
}
