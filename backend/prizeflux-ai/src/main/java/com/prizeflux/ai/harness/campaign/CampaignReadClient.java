package com.prizeflux.ai.harness.campaign;

import java.time.Duration;

/** A trusted host supplies the verified caller's token; the model never sees it. */
@FunctionalInterface
public interface CampaignReadClient {
    CampaignSummary read(long campaignId, String bearerToken, Duration remaining);
}
