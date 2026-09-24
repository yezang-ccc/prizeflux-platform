package com.prizeflux.ai.harness.demo;

import com.prizeflux.ai.harness.model.AgentMessage;
import java.util.List;

/** Offline entry point. Every activity shown here is synthetic. */
public final class HarnessDemo {
    private HarnessDemo() { }

    public static void main(String[] args) {
        if (args.length > 1 || args.length == 1 && !"--eval".equals(args[0])) {
            throw new IllegalArgumentException("usage: HarnessDemo [--eval]");
        }
        List<DemoEvaluation.CaseResult> cases = DemoEvaluation.run();
        System.out.println("PrizeFlux Agent Harness - SIMULATED / offline / no real Campaign data");
        if (args.length == 0) {
            DemoEvaluation.CaseResult normal = cases.get(0);
            for (AgentMessage message : normal.result().transcript()) {
                String value = message.kind() == AgentMessage.Kind.ASSISTANT_TOOL_CALL
                        ? message.call().name() : message.content();
                System.out.println(message.kind() + ": " + value);
            }
            System.out.println("status=" + normal.result().status());
            return;
        }
        boolean allPassed = true;
        for (DemoEvaluation.CaseResult item : cases) {
            System.out.println((item.passed() ? "PASS " : "FAIL ") + item.name()
                    + " status=" + item.result().status()
                    + " modelTurns=" + item.result().modelTurns()
                    + " toolCalls=" + item.result().toolCalls()
                    + " executedOperations=" + item.executedOperations());
            allPassed &= item.passed();
        }
        if (!allPassed) {
            System.exit(1);
        }
    }
}
