package com.prizeflux.ai.harness.safety;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Registered by application code; model output cannot create definitions. */
public record ToolDefinition(String name, Role minimumRole,
                             Map<String, ArgumentRule> arguments, ReadOnlyOperation operation) {
    private static final Set<String> RESERVED = Set.of("userid", "user_id", "role", "actor",
            "tenantid", "tenant_id", "authorization", "token");

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(minimumRole, "minimumRole");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(operation, "operation");
        if (!name.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid tool name");
        }
        arguments.forEach((key, rule) -> {
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
                    || RESERVED.contains(key.toLowerCase(java.util.Locale.ROOT)) || rule == null) {
                throw new IllegalArgumentException("invalid tool argument definition");
            }
        });
        arguments = Map.copyOf(arguments);
    }
}
