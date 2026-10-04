package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;

import java.time.Instant;

/** 活动管理用例端口，供Controller依赖且不暴露HTTP或持久化类型。 */
public interface CampaignUseCase {

    Campaign create(long id, String name, Instant startsAt, Instant endsAt);

    Campaign createOwned(String name, Instant startsAt, Instant endsAt, long ownerUserId);

    Campaign get(long id);

    Campaign publish(long id);

    Campaign close(long id);
}
