package com.prizeflux.ai.api;

import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** Shared strict parser for a request token; tokens are never placed in DTOs or session state. */
public final class AiBearerToken {
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{1,8192}");

    private AiBearerToken() { }

    public static String parse(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AiRequestException(HttpStatus.UNAUTHORIZED);
        }
        String token = authorization.substring("Bearer ".length());
        if (!SAFE_TOKEN.matcher(token).matches()) {
            throw new AiRequestException(HttpStatus.UNAUTHORIZED);
        }
        return token;
    }
}
