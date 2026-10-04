package com.prizeflux.ai.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.ai.harness.application.ModelPort;
import com.prizeflux.ai.harness.application.ModelWorkflowFactory;
import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/** Fake remains offline; provider mode synthesizes text only after a server-selected read-only tool. */
public final class ConfiguredModelWorkflowFactory implements ModelWorkflowFactory {
    private final boolean simulated;
    private final OpenAiCompatibleModelAdapter remote;

    private ConfiguredModelWorkflowFactory(boolean simulated, OpenAiCompatibleModelAdapter remote) {
        this.simulated = simulated;
        this.remote = remote;
    }

    public static ConfiguredModelWorkflowFactory fake() {
        return new ConfiguredModelWorkflowFactory(true, null);
    }

    public static ConfiguredModelWorkflowFactory openAiCompatible(ObjectMapper json, URI baseUrl,
            String model, String apiKey, Duration connectTimeout, Duration requestTimeout,
            int maxOutputTokens, int maxTranscriptChars, int maxOutputChars, int maxResponseBytes) {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Invalid model connect timeout");
        }
        ChatCompletionTransport transport = new JdkChatCompletionTransport(connectTimeout);
        OpenAiCompatibleModelAdapter adapter = new OpenAiCompatibleModelAdapter(transport, json,
                baseUrl, model, apiKey, requestTimeout, maxOutputTokens,
                maxTranscriptChars, maxOutputChars, maxResponseBytes);
        return new ConfiguredModelWorkflowFactory(false, adapter);
    }

    @Override
    public ModelPort forReadOnlyWorkflow(ToolCall serverSelectedTool, String systemInstruction,
            Function<List<AgentMessage>, String> fakeAnswer) {
        return (transcript, remaining) -> {
            if (!currentTurnHasObservation(transcript)) return serverSelectedTool;
            if (simulated) return new ModelAction.FinalAnswer(fakeAnswer.apply(transcript));
            return new ModelAction.FinalAnswer(remote.answer(systemInstruction, transcript, remaining));
        };
    }

    @Override
    public boolean simulated() {
        return simulated;
    }

    private static boolean currentTurnHasObservation(List<AgentMessage> transcript) {
        int latestUser = -1;
        for (int index = 0; index < transcript.size(); index++) {
            if (transcript.get(index).kind() == AgentMessage.Kind.USER) latestUser = index;
        }
        return latestUser >= 0 && transcript.subList(latestUser + 1, transcript.size()).stream()
                .anyMatch(message -> message.kind() == AgentMessage.Kind.TOOL_OBSERVATION);
    }
}
