package com.prizeflux.ai.harness.session;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Server-generated opaque conversation identifier; never contains a caller path. */
public record SessionId(UUID value) {
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionId random() {
        return new SessionId(UUID.randomUUID());
    }

    public static SessionId parse(String text) {
        if (text == null || !CANONICAL_UUID.matcher(text).matches()) {
            throw new IllegalArgumentException("invalid session id");
        }
        return new SessionId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
