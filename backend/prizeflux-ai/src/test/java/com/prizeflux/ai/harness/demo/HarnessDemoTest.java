package com.prizeflux.ai.harness.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prizeflux.ai.harness.application.RunStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessDemoTest {
    @Test
    void fixedEvaluationIsRepeatableAndCoversThreeOutcomes() {
        List<DemoEvaluation.CaseResult> first = DemoEvaluation.run();
        List<DemoEvaluation.CaseResult> second = DemoEvaluation.run();
        assertEquals(first, second);
        assertEquals(List.of("authorized-read", "forbidden-write", "tool-limit"),
                first.stream().map(DemoEvaluation.CaseResult::name).toList());
        assertTrue(first.stream().allMatch(DemoEvaluation.CaseResult::passed));
        assertEquals(RunStatus.TOOL_CALL_LIMIT, first.get(2).result().status());
        assertEquals(0, first.get(1).executedOperations());
    }

    @Test
    void demoOutputIsExplicitlySimulatedAndContainsNoToken() {
        PrintStream previous = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            HarnessDemo.main(new String[0]);
        } finally {
            System.setOut(previous);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("SIMULATED / offline"));
        assertTrue(output.contains("ASSISTANT_TOOL_CALL: campaign.summary"));
        assertTrue(output.contains("TOOL_OBSERVATION:"));
        assertTrue(output.contains("status=COMPLETED"));
        assertTrue(!output.contains("Bearer "));
    }
}
