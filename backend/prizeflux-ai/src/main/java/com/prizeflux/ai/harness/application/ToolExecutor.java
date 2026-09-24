package com.prizeflux.ai.harness.application;

import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import java.time.Duration;

/** The future safety module supplies authorization and a read-only allowlist. */
@FunctionalInterface
public interface ToolExecutor {
    ToolResult execute(ToolCall call, Duration remaining);
}
