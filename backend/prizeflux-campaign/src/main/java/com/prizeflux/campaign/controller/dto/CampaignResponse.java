package com.prizeflux.campaign.controller.dto;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;

import java.time.Instant;

/** 对外只读活动投影，不暴露持久化行对象或审计字段。 */
public record CampaignResponse(
        long id,
        String name,
        Instant startsAt,
        Instant endsAt,
        CampaignStatus status) {

    /** 从合法领域快照创建响应。 */
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
                campaign.id(),
                campaign.name(),
                campaign.startsAt(),
                campaign.endsAt(),
                campaign.status());
    }
}
