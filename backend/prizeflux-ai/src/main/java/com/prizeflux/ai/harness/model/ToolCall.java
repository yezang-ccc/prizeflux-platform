package com.prizeflux.ai.harness.model;

import java.util.Map;
import java.util.Objects;

/** A declarative request, never an executable command. */
public record ToolCall(String name, Map<String, String> arguments) implements ModelAction {
    public ToolCall {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name is blank");
        }
        arguments.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("invalid tool argument");
            }
        });
        arguments = Map.copyOf(arguments);
    }
}
