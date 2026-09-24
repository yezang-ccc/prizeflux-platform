package com.prizeflux.ai.api;

import org.springframework.http.HttpStatus;

/** Contains only a status; never carries an upstream body or credential. */
public final class AiRequestException extends RuntimeException {
    private final HttpStatus status;

    public AiRequestException(HttpStatus status) {
        super("AI request failed");
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
