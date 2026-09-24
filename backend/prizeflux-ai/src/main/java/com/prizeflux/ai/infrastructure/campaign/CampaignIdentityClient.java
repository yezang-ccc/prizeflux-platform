package com.prizeflux.ai.infrastructure.campaign;

import com.prizeflux.ai.harness.safety.Actor;

@FunctionalInterface
public interface CampaignIdentityClient {
    Actor verify(String bearerToken);
}
