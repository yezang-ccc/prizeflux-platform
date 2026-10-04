package com.prizeflux.ai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the AI service over HTTP against a contract-shaped local Campaign service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiCampaignEndToEndTest {
    private static final AtomicInteger identityRequests = new AtomicInteger();
    private static final AtomicInteger campaignReads = new AtomicInteger();
    private static final AtomicInteger draftValidationRequests = new AtomicInteger();
    private static final AtomicReference<String> campaignBearer = new AtomicReference<>();
    private static final AtomicReference<String> draftValidationBearer = new AtomicReference<>();
    private static final HttpServer campaignServer = startCampaignStub();

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void campaignBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("prizeflux.campaign.base-url", () -> "http://127.0.0.1:" + campaignServer.getAddress().getPort());
    }

    @BeforeEach
    void resetCounters() {
        identityRequests.set(0);
        campaignReads.set(0);
        draftValidationRequests.set(0);
        campaignBearer.set(null);
        draftValidationBearer.set(null);
    }

    @AfterAll
    static void stopCampaignStub() {
        campaignServer.stop(0);
    }

    @Test
    void authenticatedOperatorTraversesAiAndCampaignHttpContracts() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("operator-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange("/api/ai/campaign-summary", HttpMethod.POST,
                new HttpEntity<>("{\"campaignId\":1005,\"question\":\"What is the status?\"}", headers),
                String.class);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PFX-0000", body.path("code").asText());
        assertTrue(body.path("data").path("simulated").asBoolean());
        assertTrue(body.path("data").path("answer").asText().contains("Autumn Campaign"));
        assertEquals(1, identityRequests.get());
        assertEquals(1, campaignReads.get());
        assertEquals("operator-token", campaignBearer.get());
    }

    @Test
    void userIsDeniedBeforeAnyCampaignRead() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("user-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange("/api/ai/campaign-summary", HttpMethod.POST,
                new HttpEntity<>("{\"campaignId\":1005,\"question\":\"status?\"}", headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(1, identityRequests.get());
        assertEquals(0, campaignReads.get());
    }

    @Test
    void anonymousRequestNeverCallsCampaign() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange("/api/ai/campaign-summary", HttpMethod.POST,
                new HttpEntity<>("{\"campaignId\":1005,\"question\":\"status?\"}", headers), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(0, identityRequests.get());
        assertEquals(0, campaignReads.get());
    }

    @Test
    void userCopilotCallsOnlyTheAuthenticatedReadOnlyCampaignValidator() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("user-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String proposal = """
                {"name":"Draft","startsAt":"2026-10-01T00:00:00Z","endsAt":"2026-10-08T00:00:00Z",
                 "dailyLimit":3,"prizes":[{"id":1,"name":"Gift","totalStock":2,"probability":0.25}]}
                """;
        ResponseEntity<String> response = http.exchange("/api/ai/creator/validate", HttpMethod.POST,
                new HttpEntity<>(proposal, headers), String.class);
        JsonNode body = new ObjectMapper().readTree(response.getBody());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(body.path("data").path("valid").asBoolean());
        assertTrue(body.path("data").path("simulated").asBoolean());
        assertEquals("campaign.draft.validate", body.path("data").path("trace").get(1).path("toolName").asText());
        assertEquals(1, identityRequests.get());
        assertEquals(1, draftValidationRequests.get());
        assertEquals("user-token", draftValidationBearer.get());
        assertEquals(0, campaignReads.get());
    }

    private static HttpServer startCampaignStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/auth/me", exchange -> {
                identityRequests.incrementAndGet();
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                boolean isUser = "Bearer user-token".equals(auth);
                String role = isUser ? "USER" : "OPERATOR";
                byte[] body = ("{\"code\":\"PFX-0000\",\"message\":\"ok\",\"data\":"
                        + "{\"userId\":7,\"username\":\"demo-user\",\"role\":\"" + role + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/api/campaigns/1005", exchange -> {
                campaignReads.incrementAndGet();
                campaignBearer.set(exchange.getRequestHeaders().getFirst("Authorization").replace("Bearer ", ""));
                byte[] body = ("{\"code\":\"PFX-0000\",\"message\":\"ok\",\"data\":"
                        + "{\"id\":1005,\"name\":\"Autumn Campaign\",\"startsAt\":null,"
                        + "\"endsAt\":null,\"status\":\"PUBLISHED\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/api/me/campaigns/validate", exchange -> {
                draftValidationRequests.incrementAndGet();
                draftValidationBearer.set(exchange.getRequestHeaders().getFirst("Authorization")
                        .replace("Bearer ", ""));
                byte[] body = """
                        {"code":"PFX-0000","message":"ok","data":{"valid":true,"issues":[]}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
