package com.prizeflux.ai.harness.safety;

import com.prizeflux.ai.harness.model.ToolResult;
import java.time.Duration;
import java.util.Map;

/** Implementations must enforce resource ownership and I/O timeout themselves. */
@FunctionalInterface
public interface ReadOnlyOperation {
    ToolResult execute(Actor actor, Map<String, String> arguments, Duration remaining);
}
