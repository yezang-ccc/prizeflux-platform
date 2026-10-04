package com.prizeflux.ai.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Proposed, unsaved creator form sent to the read-only draft copilot. */
public record AiCreatorDraftRequest(String name, Instant startsAt, Instant endsAt,
        int dailyLimit, List<PrizeProposal> prizes) {
    public AiCreatorDraftRequest {
        if (prizes != null && prizes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("draft proposal contains a null prize");
        }
        prizes = prizes == null ? List.of() : List.copyOf(prizes);
        if (prizes.size() > 50 || name != null && name.length() > 100) {
            throw new IllegalArgumentException("draft proposal exceeds limits");
        }
    }

    public record PrizeProposal(long id, String name, int totalStock, BigDecimal probability) {
    }
}
