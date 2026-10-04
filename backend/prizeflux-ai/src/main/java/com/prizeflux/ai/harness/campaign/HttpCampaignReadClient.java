package com.prizeflux.ai.harness.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.contract.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Fixed GET adapter. No model-controlled host, HTTP method, redirects or response passthrough. */
public final class HttpCampaignReadClient implements CampaignReadClient {
    private static final int MAX_BODY_BYTES = 8192;
    private static final Duration MAX_REQUEST_TIME = Duration.ofSeconds(5);
    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpCampaignReadClient(URI baseUri) {
        this(baseUri, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper());
    }

    HttpCampaignReadClient(URI baseUri, HttpClient http, ObjectMapper json) {
        Objects.requireNonNull(baseUri, "baseUri");
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost() == null ? "" : baseUri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || baseUri.getHost() == null
                || "http".equalsIgnoreCase(scheme)
                    && !java.util.Set.of("localhost", "127.0.0.1", "::1", "campaign").contains(host)
                || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null
                || !(baseUri.getPath().isEmpty() || "/".equals(baseUri.getPath()))) {
            throw new IllegalArgumentException("invalid Campaign base URI");
        }
        this.baseUri = baseUri;
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CampaignSummary read(long campaignId, String bearerToken, Duration remaining) {
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(remaining, "remaining");
        if (campaignId <= 0 || bearerToken.isBlank() || bearerToken.length() > 8192
                || !bearerToken.matches("[A-Za-z0-9._~+/=-]+")
                || remaining.isNegative() || remaining.isZero()) {
            throw new IllegalArgumentException("invalid Campaign read request");
        }
        Duration timeout = remaining.compareTo(MAX_REQUEST_TIME) < 0 ? remaining : MAX_REQUEST_TIME;
        URI target = baseUri.resolve("/api/campaigns/" + campaignId);
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Campaign read unavailable");
                }
                byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new IllegalStateException("Campaign response too large");
                }
                JsonNode root = json.readTree(bytes);
                if (root == null || !ErrorCode.SUCCESS.code().equals(root.path("code").asText())
                        || !root.path("data").isObject()) {
                    throw new IllegalStateException("Campaign response invalid");
                }
                JsonNode data = root.path("data");
                if (!data.path("id").isIntegralNumber() || data.path("id").asLong() != campaignId
                        || !data.path("name").isTextual() || !data.path("status").isTextual()) {
                    throw new IllegalStateException("Campaign response invalid");
                }
                return new CampaignSummary(campaignId, data.path("name").asText(),
                        data.path("status").asText());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Campaign read unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Campaign read interrupted");
        }
    }
}
