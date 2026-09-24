package com.prizeflux.ai.harness.campaign;

import java.util.Objects;
import java.util.Set;

/** Only public Campaign response fields needed by the read-only tool. */
public record CampaignSummary(long id, String name, String status) {
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "CLOSED");

    public CampaignSummary {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        if (id <= 0 || name.isBlank() || name.length() > 200 || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("invalid campaign summary");
        }
    }
}
