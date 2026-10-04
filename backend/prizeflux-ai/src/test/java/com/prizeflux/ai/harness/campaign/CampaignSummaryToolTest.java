package com.prizeflux.ai.harness.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.AuthorizedToolExecutor;
import com.prizeflux.ai.harness.safety.Role;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CampaignSummaryToolTest {
    private static final String FAKE_TOKEN = "header.payload.signature";
    private final AtomicReference<Response> response = new AtomicReference<>(new Response(200,
            "{\"code\":\"PFX-0000\",\"message\":\"成功\",\"data\":{\"id\":1005,"
                    + "\"name\":\"Autumn Campaign\",\"status\":\"PUBLISHED\"}}"));
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/campaigns/1005", exchange -> {
            requestCount.incrementAndGet();
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            Response configured = response.get();
            byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
            if (configured.status() == 302) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
            }
            exchange.sendResponseHeaders(configured.status(), body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fixedGetUsesTrustedTokenAndReturnsOnlySelectedFields() {
        ToolResult result = operatorTool().execute(call(Map.of("campaignId", "1005")), Duration.ofSeconds(2));
        assertTrue(result.success());
        assertEquals("GET", method.get());
        assertEquals("Bearer " + FAKE_TOKEN, authorization.get());
        assertEquals(1, requestCount.get());
        assertTrue(result.content().contains("Autumn Campaign"));
        assertFalse(result.content().contains(FAKE_TOKEN));
    }

    @Test
    void userAndModelSpoofingCannotReachHttp() {
        AuthorizedToolExecutor userTool = CampaignSummaryTool.forSession(new Actor(8, Role.USER),
                FAKE_TOKEN, client());
        assertEquals("TOOL_FORBIDDEN", userTool.execute(call(Map.of("campaignId", "1005")),
                Duration.ofSeconds(2)).content());
        assertEquals("INVALID_ARGUMENTS", operatorTool().execute(call(Map.of(
                "campaignId", "1005", "userId", "1")), Duration.ofSeconds(2)).content());
        assertEquals("INVALID_ARGUMENTS", operatorTool().execute(call(Map.of(
                "campaignId", "1005", "url", "http://127.0.0.1/admin")), Duration.ofSeconds(2)).content());
        assertEquals("INVALID_ARGUMENTS", operatorTool().execute(call(Map.of(
                "campaignId", "1005 OR 1=1")), Duration.ofSeconds(2)).content());
        assertEquals(0, requestCount.get());
    }

    @Test
    void remoteFailuresAndRedirectsDoNotLeakBodyOrToken() {
        for (int status : new int[] {401, 403, 404, 302}) {
            response.set(new Response(status, "private token=" + FAKE_TOKEN));
            ToolResult result = operatorTool().execute(call(Map.of("campaignId", "1005")),
                    Duration.ofSeconds(2));
            assertFalse(result.success());
            assertEquals("TOOL_UNAVAILABLE", result.content());
            assertFalse(result.content().contains(FAKE_TOKEN));
        }
        assertEquals(4, requestCount.get());
    }

    @Test
    void malformedOrOversizedResponseIsRejected() {
        response.set(new Response(200, "{\"code\":\"PFX-0000\",\"data\":{\"id\":999}}"));
        assertEquals("TOOL_UNAVAILABLE", operatorTool().execute(call(Map.of("campaignId", "1005")),
                Duration.ofSeconds(2)).content());
        response.set(new Response(200, "x".repeat(8193)));
        assertEquals("TOOL_UNAVAILABLE", operatorTool().execute(call(Map.of("campaignId", "1005")),
                Duration.ofSeconds(2)).content());
    }

    @Test
    void invalidTimeAndConfigurationNeverReachNetwork() {
        ToolResult timedOut = operatorTool().execute(call(Map.of("campaignId", "1005")), Duration.ZERO);
        assertEquals("TIME_BUDGET_EXCEEDED", timedOut.content());
        assertEquals(0, requestCount.get());
        assertThrows(IllegalArgumentException.class,
                () -> new HttpCampaignReadClient(URI.create("file:///etc/passwd")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpCampaignReadClient(URI.create("http://user:password@localhost")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpCampaignReadClient(URI.create("http://localhost/internal")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpCampaignReadClient(URI.create("http://campaign.internal")));
        // This exact hostname is the trusted service alias on the isolated Compose network.
        new HttpCampaignReadClient(URI.create("http://campaign:8081"));
        assertThrows(IllegalArgumentException.class,
                () -> CampaignSummaryTool.forSession(new Actor(8, Role.OPERATOR), " ", client()));
    }

    private AuthorizedToolExecutor operatorTool() {
        return CampaignSummaryTool.forSession(new Actor(8, Role.OPERATOR), FAKE_TOKEN, client());
    }

    private HttpCampaignReadClient client() {
        return new HttpCampaignReadClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private ToolCall call(Map<String, String> arguments) {
        return new ToolCall("campaign.summary", arguments);
    }

    private record Response(int status, String body) { }
}
