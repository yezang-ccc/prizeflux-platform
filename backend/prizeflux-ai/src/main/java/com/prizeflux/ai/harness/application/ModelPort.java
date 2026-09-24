package com.prizeflux.ai.harness.application;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import java.time.Duration;
import java.util.List;

/** Adapters must enforce their own I/O timeout using the supplied remaining budget. */
@FunctionalInterface
public interface ModelPort {
    ModelAction next(List<AgentMessage> transcript, Duration remaining);
}
