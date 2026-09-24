package com.prizeflux.ai.harness.context;

import com.prizeflux.ai.harness.model.AgentMessage;
import java.util.List;
import java.util.Objects;

/** Read-only derived model input. It never replaces the session's archived event list. */
public record ContextWindow(List<AgentMessage> messages, int omittedMessageCount,
                            int characterCount, int currentMessageIndex) {
    public ContextWindow {
        messages = List.copyOf(messages);
        if (messages.isEmpty() || omittedMessageCount < 0 || characterCount < 0
                || currentMessageIndex < 0 || currentMessageIndex >= messages.size()
                || messages.get(currentMessageIndex).kind() != AgentMessage.Kind.USER) {
            throw new IllegalArgumentException("invalid context window");
        }
    }
}
