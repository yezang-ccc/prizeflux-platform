package com.prizeflux.ai.harness.safety;

import java.util.Objects;
import java.util.regex.Pattern;

/** Explicit value shape, avoiding interpretation of arbitrary model text. */
public record ArgumentRule(boolean required, Pattern pattern, int maxLength) {
    public ArgumentRule {
        Objects.requireNonNull(pattern, "pattern");
        if (maxLength < 1 || maxLength > 1024) {
            throw new IllegalArgumentException("invalid maxLength");
        }
    }

    public boolean accepts(String value) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && pattern.matcher(value).matches();
    }
}
