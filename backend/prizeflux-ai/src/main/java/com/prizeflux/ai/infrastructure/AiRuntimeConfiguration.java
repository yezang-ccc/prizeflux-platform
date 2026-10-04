package com.prizeflux.ai.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.ai.harness.campaign.CampaignReadClient;
import com.prizeflux.ai.harness.campaign.HttpCampaignReadClient;
import com.prizeflux.ai.infrastructure.campaign.CampaignDraftValidationClient;
import com.prizeflux.ai.infrastructure.campaign.CampaignIdentityClient;
import com.prizeflux.ai.infrastructure.campaign.HttpCampaignDraftValidationClient;
import com.prizeflux.ai.infrastructure.campaign.HttpCampaignIdentityClient;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiRuntimeConfiguration {
    @Bean
    CampaignReadClient campaignReadClient(@Value("${prizeflux.campaign.base-url}") URI baseUrl) {
        return new HttpCampaignReadClient(baseUrl);
    }

    @Bean
    CampaignIdentityClient campaignIdentityClient(@Value("${prizeflux.campaign.base-url}") URI baseUrl,
                                                   ObjectMapper objectMapper) {
        return new HttpCampaignIdentityClient(baseUrl, objectMapper);
    }

    @Bean
    CampaignDraftValidationClient campaignDraftValidationClient(
            @Value("${prizeflux.campaign.base-url}") URI baseUrl, ObjectMapper objectMapper) {
        return new HttpCampaignDraftValidationClient(baseUrl, objectMapper);
    }

    @Bean
    Clock aiClock() {
        return Clock.systemUTC();
    }
}
