package com.prizeflux.ai.infrastructure.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.ai.api.AiCreatorDraftRequest;
import com.prizeflux.ai.api.AiRequestException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fixed-target authenticated POST to Campaign; this endpoint validates but never writes. */
public final class HttpCampaignDraftValidationClient implements CampaignDraftValidationClient {
    private static final int MAX_BODY_BYTES = 8192;
    private static final Duration MAX_REQUEST_TIME = Duration.ofSeconds(4);
    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpCampaignDraftValidationClient(URI baseUri, ObjectMapper json) {
        Objects.requireNonNull(baseUri, "baseUri");
        String host = baseUri.getHost() == null ? "" : baseUri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null
                || "http".equalsIgnoreCase(baseUri.getScheme())
                    && !java.util.Set.of("localhost", "127.0.0.1", "::1", "campaign").contains(host)
                || baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null
                || !(baseUri.getPath().isEmpty() || "/".equals(baseUri.getPath()))) {
            throw new IllegalArgumentException("invalid Campaign base URI");
        }
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Result validate(AiCreatorDraftRequest proposal, String bearerToken, Duration remaining) {
        Objects.requireNonNull(proposal, "proposal");
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > 8192
                || !bearerToken.matches("[A-Za-z0-9._~+/=-]+")
                || remaining == null || remaining.isNegative() || remaining.isZero()) {
            throw new IllegalArgumentException("invalid draft validation request");
        }
        byte[] requestBytes;
        try {
            requestBytes = json.writeValueAsBytes(proposal);
        } catch (IOException exception) {
            throw new IllegalStateException("draft validation unavailable");
        }
        if (requestBytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("draft proposal too large");
        }
        Duration timeout = remaining.compareTo(MAX_REQUEST_TIME) < 0 ? remaining : MAX_REQUEST_TIME;
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/me/campaigns/validate"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes)).build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 401) throw new AiRequestException(HttpStatus.UNAUTHORIZED);
                if (response.statusCode() == 403) throw new AiRequestException(HttpStatus.FORBIDDEN);
                byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) throw new AiRequestException(HttpStatus.BAD_GATEWAY);
                JsonNode root = json.readTree(bytes);
                if (response.statusCode() == 400 && root != null
                        && ErrorCode.INVALID_ARGUMENT.code().equals(root.path("code").asText())) {
                    return new Result(false, List.of("请检查活动时间、每日上限和奖品字段是否完整。"));
                }
                if (response.statusCode() != 200 || root == null
                        || !ErrorCode.SUCCESS.code().equals(root.path("code").asText())
                        || !root.path("data").isObject()
                        || !root.path("data").path("valid").isBoolean()
                        || !root.path("data").path("issues").isArray()) {
                    throw new AiRequestException(HttpStatus.BAD_GATEWAY);
                }
                List<String> issues = new ArrayList<>();
                for (JsonNode issue : root.path("data").path("issues")) {
                    if (!issue.isTextual() || issue.asText().length() > 300 || issues.size() >= 20) {
                        throw new AiRequestException(HttpStatus.BAD_GATEWAY);
                    }
                    issues.add(issue.asText());
                }
                return new Result(root.path("data").path("valid").asBoolean(), issues);
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
