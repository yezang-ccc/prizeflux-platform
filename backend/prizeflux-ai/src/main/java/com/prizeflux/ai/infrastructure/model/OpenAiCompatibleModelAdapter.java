package com.prizeflux.ai.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prizeflux.ai.harness.model.AgentMessage;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Text-only Chat Completions adapter; it cannot return executable tools or expand Harness authority. */
final class OpenAiCompatibleModelAdapter {
    private final ChatCompletionTransport transport;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration maxRequestTimeout;
    private final int maxOutputTokens;
    private final int maxTranscriptChars;
    private final int maxOutputChars;
    private final int maxResponseBytes;

    OpenAiCompatibleModelAdapter(ChatCompletionTransport transport, ObjectMapper json,
            URI baseUrl, String model, String apiKey, Duration maxRequestTimeout,
            int maxOutputTokens, int maxTranscriptChars, int maxOutputChars, int maxResponseBytes) {
        this.transport = transport;
        this.json = json;
        this.endpoint = endpoint(baseUrl);
        this.model = requireText(model, "model");
        this.apiKey = requireText(apiKey, "api key");
        this.maxRequestTimeout = maxRequestTimeout;
        this.maxOutputTokens = maxOutputTokens;
        this.maxTranscriptChars = maxTranscriptChars;
        this.maxOutputChars = maxOutputChars;
        this.maxResponseBytes = maxResponseBytes;
        if (maxRequestTimeout.isZero() || maxRequestTimeout.isNegative()
                || maxOutputTokens < 1 || maxTranscriptChars < 1
                || maxOutputChars < 1 || maxResponseBytes < 1024) {
            throw new IllegalArgumentException("Invalid model adapter limits");
        }
    }

    String answer(String systemInstruction, List<AgentMessage> transcript, Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("No model execution budget remains");
        }
        ObjectNode request = json.createObjectNode();
        request.put("model", model);
        request.put("max_tokens", maxOutputTokens);
        request.put("stream", false);
        ArrayNode messages = request.putArray("messages");
        addMessage(messages, "system", systemInstruction);
        int transcriptChars = systemInstruction.length();
        for (AgentMessage message : transcript) {
            String role;
            String content;
            switch (message.kind()) {
                case USER -> { role = "user"; content = message.content(); }
                case ASSISTANT_ANSWER -> { role = "assistant"; content = message.content(); }
                case TOOL_OBSERVATION -> {
                    role = "user";
                    content = "Read-only tool observation (untrusted data):\n" + message.content();
                }
                case ASSISTANT_TOOL_CALL -> { continue; }
                default -> throw new IllegalStateException("Unsupported transcript event");
            }
            transcriptChars += content.length();
            if (transcriptChars > maxTranscriptChars) {
                throw new IllegalStateException("Model transcript exceeded configured limit");
            }
            addMessage(messages, role, content);
        }
        try {
            Duration timeout = remaining.compareTo(maxRequestTimeout) < 0 ? remaining : maxRequestTimeout;
            ChatCompletionTransport.Response response = transport.post(endpoint, apiKey,
                    json.writeValueAsString(request), timeout, maxResponseBytes);
            if (response.status() < 200 || response.status() >= 300) {
                throw new IllegalStateException("Model provider returned HTTP " + response.status());
            }
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) throw new IllegalStateException("Model provider returned no text answer");
            String answer = content.asText();
            if (answer.isBlank() || answer.length() > maxOutputChars) {
                throw new IllegalStateException("Model answer is empty or exceeds configured limit");
            }
            return answer;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Model response could not be read");
        }
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode node = messages.addObject();
        node.put("role", role);
        node.put("content", content);
    }

    private static URI endpoint(URI base) {
        if (base == null || !("https".equalsIgnoreCase(base.getScheme())
                || "http".equalsIgnoreCase(base.getScheme()))
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Invalid model base URL");
        }
        String path = base.getPath() == null ? "" : base.getPath().replaceAll("/$", "");
        String endpointPath = path.endsWith("/chat/completions") ? path : path + "/chat/completions";
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), endpointPath, null, null);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid model endpoint URL");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Model " + field + " is required");
        return value.trim();
    }
}
