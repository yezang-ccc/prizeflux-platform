package com.prizeflux.ai.infrastructure.campaign;

import com.prizeflux.ai.api.AiCreatorDraftRequest;

import java.time.Duration;
import java.util.List;

/** Calls Campaign's authenticated, side-effect-free source-of-truth validator. */
public interface CampaignDraftValidationClient {
    record Result(boolean valid, List<String> issues) {
        public Result {
            issues = List.copyOf(issues);
        }
    }

    Result validate(AiCreatorDraftRequest request, String bearerToken, Duration remaining);
}
