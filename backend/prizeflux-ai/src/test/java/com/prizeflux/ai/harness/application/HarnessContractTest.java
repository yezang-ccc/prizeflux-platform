package com.prizeflux.ai.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessContractTest {
    @Test
    void toolArgumentsAndTranscriptAreImmutableSnapshots() {
        Map<String, String> supplied = new HashMap<>();
        supplied.put("campaignId", "1005");
        ToolCall call = new ToolCall("campaign.read", supplied);
        supplied.put("campaignId", "9999");
        assertEquals("1005", call.arguments().get("campaignId"));
        assertThrows(UnsupportedOperationException.class,
                () -> call.arguments().put("campaignId", "1"));

        List<AgentMessage> mutable = new java.util.ArrayList<>();
        mutable.add(AgentMessage.user("status?"));
        RunResult result = new RunResult(RunStatus.MODEL_TURN_LIMIT, null, mutable, 1, 0);
        mutable.clear();
        assertEquals(1, result.transcript().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.transcript().add(AgentMessage.user("more")));
    }

    @Test
    void rejectsInvalidContracts() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall(" ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("read", Map.of(" ", "x")));
        assertThrows(IllegalArgumentException.class, () -> new ModelAction.FinalAnswer(" "));
        assertThrows(IllegalArgumentException.class, () -> AgentMessage.user(""));
        assertThrows(IllegalArgumentException.class,
                () -> new RunLimits(0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RunLimits(1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RunResult(RunStatus.COMPLETED, null, List.of(), 1, 0));
    }

    @Test
    void observationRetainsToolFailureAsData() {
        ToolCall call = new ToolCall("campaign.read", Map.of());
        AgentMessage observation = AgentMessage.toolObservation(call, ToolResult.error("not available"));
        assertEquals(AgentMessage.Kind.TOOL_OBSERVATION, observation.kind());
        assertEquals(false, observation.toolSuccess());
    }
}
