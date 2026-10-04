package com.prizeflux.ai.infrastructure.model;

import java.net.URI;
import java.time.Duration;

/** Small HTTP boundary so provider contract tests never contact an external model. */
@FunctionalInterface
interface ChatCompletionTransport {
    Response post(URI endpoint, String apiKey, String body, Duration timeout, int maxResponseBytes);

    record Response(int status, String body) { }
}
