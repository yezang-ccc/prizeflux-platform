package com.prizeflux.ai.infrastructure.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.ai.api.AiRequestException;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.Role;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** Delegates JWT verification and role resolution to Campaign's authenticated /me endpoint. */
public final class HttpCampaignIdentityClient implements CampaignIdentityClient {
    private static final int MAX_BODY_BYTES = 4096;
    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpCampaignIdentityClient(URI baseUri, ObjectMapper json) {
        Objects.requireNonNull(baseUri, "baseUri");
        String host = baseUri.getHost() == null ? "" : baseUri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null
                || "http".equalsIgnoreCase(baseUri.getScheme())
                    && !java.util.Set.of("localhost", "127.0.0.1", "::1", "campaign").contains(host)
                || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null
                || !(baseUri.getPath().isEmpty() || "/".equals(baseUri.getPath()))) {
            throw new IllegalArgumentException("invalid Campaign base URI");
        }
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Actor verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > 8192
                || !bearerToken.matches("[A-Za-z0-9._~+/=-]+")) {
            throw new AiRequestException(HttpStatus.UNAUTHORIZED);
        }
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/auth/me"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 401) {
                    throw new AiRequestException(HttpStatus.UNAUTHORIZED);
                }
                if (response.statusCode() == 403) {
                    throw new AiRequestException(HttpStatus.FORBIDDEN);
                }
                if (response.statusCode() != 200) {
                    throw new AiRequestException(HttpStatus.BAD_GATEWAY);
                }
                byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new AiRequestException(HttpStatus.BAD_GATEWAY);
                }
                JsonNode root = json.readTree(bytes);
                JsonNode data = root == null ? null : root.path("data");
                if (root == null || !"PFX-0000".equals(root.path("code").asText())
                        || data == null || !data.path("userId").isIntegralNumber()
                        || !data.path("userId").canConvertToLong()
                        || !data.path("role").isTextual()) {
                    throw new AiRequestException(HttpStatus.UNAUTHORIZED);
                }
                Role role;
                try {
                    role = Role.valueOf(data.path("role").asText());
                    return new Actor(data.path("userId").asLong(), role);
                } catch (IllegalArgumentException exception) {
                    throw new AiRequestException(HttpStatus.UNAUTHORIZED);
                }
            }
        } catch (AiRequestException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AiRequestException(HttpStatus.BAD_GATEWAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiRequestException(HttpStatus.BAD_GATEWAY);
        }
    }
}
