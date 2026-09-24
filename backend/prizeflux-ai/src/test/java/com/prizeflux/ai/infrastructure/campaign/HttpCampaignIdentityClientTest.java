package com.prizeflux.ai.infrastructure.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.ai.api.AiRequestException;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.Role;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HttpCampaignIdentityClientTest {
    private final AtomicReference<Response> response = new AtomicReference<>(new Response(200,
            "{\"code\":\"PFX-0000\",\"data\":{\"userId\":17,\"username\":\"operator\","
                    + "\"role\":\"OPERATOR\"}}"));
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/me", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            Response configured = response.get();
            byte[] bytes = configured.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(configured.status(), bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void asksCampaignForVerifiedIdentityAndRole() {
        Actor actor = client().verify("signed.token.value");
        assertEquals(new Actor(17, Role.OPERATOR), actor);
        assertEquals("/api/auth/me", path.get());
        assertEquals("Bearer signed.token.value", authorization.get());
        assertEquals(1, requests.get());
    }

    @Test
    void invalidTokenOrMalformedIdentityFailsClosed() {
        assertThrows(AiRequestException.class, () -> client().verify("bad token"));
        response.set(new Response(200,
                "{\"code\":\"PFX-0000\",\"data\":{\"userId\":17,\"role\":\"ADMIN\"}}"));
        AiRequestException exception = assertThrows(AiRequestException.class,
                () -> client().verify("signed.token.value"));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }

    @Test
    void upstream401IsUnauthorizedAndServerFailureIsBadGateway() {
        response.set(new Response(401, "sensitive response"));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(AiRequestException.class,
                () -> client().verify("signed.token.value")).status());
        response.set(new Response(503, "private backend detail"));
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(AiRequestException.class,
                () -> client().verify("signed.token.value")).status());
    }

    private HttpCampaignIdentityClient client() {
        return new HttpCampaignIdentityClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), new ObjectMapper());
    }

    private record Response(int status, String body) { }
}
