package com.prizeflux.ai.harness.session;

import com.prizeflux.ai.harness.model.AgentMessage;

public final class SessionTextSize {
    private SessionTextSize() { }

    public static int of(AgentMessage message) {
        int size = message.content().length();
        if (message.call() != null) {
            size += message.call().name().length();
            for (var argument : message.call().arguments().entrySet()) {
                size += argument.getKey().length() + argument.getValue().length();
            }
        }
        return size;
    }
}
