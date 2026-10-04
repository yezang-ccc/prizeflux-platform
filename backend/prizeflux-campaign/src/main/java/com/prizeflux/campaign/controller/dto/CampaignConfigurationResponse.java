package com.prizeflux.campaign.controller.dto;

import java.math.BigDecimal;
import java.util.List;

/** 运营端配置快照；totalStock 是配置总量，不是并发下的实时余量。 */
public record CampaignConfigurationResponse(int dailyLimit, List<PrizeConfigurationResponse> prizes) {
    public CampaignConfigurationResponse {
        prizes = List.copyOf(prizes);
    }

    public record PrizeConfigurationResponse(long id, String name, int totalStock, BigDecimal probability) {
    }
}
