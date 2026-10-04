package com.prizeflux.ai.harness.application;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ToolCall;
import java.util.List;
import java.util.function.Function;

/** Builds a workflow model while keeping read-only tool selection under server control. */
public interface ModelWorkflowFactory {
    ModelPort forReadOnlyWorkflow(ToolCall serverSelectedTool, String systemInstruction,
                                  Function<List<AgentMessage>, String> fakeAnswer);

    boolean simulated();
}
