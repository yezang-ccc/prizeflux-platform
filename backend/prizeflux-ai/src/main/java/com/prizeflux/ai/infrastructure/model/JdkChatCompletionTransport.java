package com.prizeflux.ai.infrastructure.model;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** JDK HTTP transport with a per-call deadline and a bounded response body. */
final class JdkChatCompletionTransport implements ChatCompletionTransport {
    private final HttpClient client;

    JdkChatCompletionTransport(Duration connectTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public Response post(URI endpoint, String apiKey, String body, Duration timeout, int maxResponseBytes) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(maxResponseBytes + 1);
                if (bytes.length > maxResponseBytes) {
                    throw new IllegalStateException("Model response exceeded configured limit");
                }
                return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model request interrupted");
        } catch (IOException exception) {
            throw new IllegalStateException("Model provider request failed");
        }
    }
}
