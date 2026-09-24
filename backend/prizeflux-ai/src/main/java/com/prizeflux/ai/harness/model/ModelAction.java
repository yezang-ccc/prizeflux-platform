package com.prizeflux.ai.harness.model;

import java.util.Objects;

/** A model may answer or request exactly one typed tool call per turn. */
public sealed interface ModelAction permits ModelAction.FinalAnswer, ToolCall {
    record FinalAnswer(String text) implements ModelAction {
        public FinalAnswer {
            Objects.requireNonNull(text, "text");
            if (text.isBlank()) {
                throw new IllegalArgumentException("answer is blank");
            }
        }
    }
}
