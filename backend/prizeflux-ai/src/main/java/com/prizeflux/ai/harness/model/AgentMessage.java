package com.prizeflux.ai.harness.model;

import java.util.Objects;

/** Immutable transcript entry. Only a tool observation carries a success flag. */
public record AgentMessage(Kind kind, String content, ToolCall call, Boolean toolSuccess) {
    public enum Kind { USER, ASSISTANT_TOOL_CALL, TOOL_OBSERVATION, ASSISTANT_ANSWER }

    public AgentMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        switch (kind) {
            case USER, ASSISTANT_ANSWER -> {
                if (content.isBlank() || call != null || toolSuccess != null) {
                    throw new IllegalArgumentException("invalid text message");
                }
            }
            case ASSISTANT_TOOL_CALL -> {
                if (!content.isEmpty() || call == null || toolSuccess != null) {
                    throw new IllegalArgumentException("invalid tool call message");
                }
            }
            case TOOL_OBSERVATION -> {
                if (call == null || toolSuccess == null) {
                    throw new IllegalArgumentException("invalid tool observation");
                }
            }
        }
    }

    public static AgentMessage user(String text) {
        return new AgentMessage(Kind.USER, text, null, null);
    }

    public static AgentMessage toolCall(ToolCall call) {
        return new AgentMessage(Kind.ASSISTANT_TOOL_CALL, "", call, null);
    }

    public static AgentMessage toolObservation(ToolCall call, ToolResult result) {
        Objects.requireNonNull(result, "result");
        return new AgentMessage(Kind.TOOL_OBSERVATION, result.content(), call, result.success());
    }

    public static AgentMessage answer(String text) {
        return new AgentMessage(Kind.ASSISTANT_ANSWER, text, null, null);
    }
}
