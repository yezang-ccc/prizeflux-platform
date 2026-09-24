package com.prizeflux.campaign.infrastructure.persistence;

import java.time.Instant;

/** 基础设施层的数据库行快照，不作为领域或接口返回类型。 */
public record CampaignPersistenceRecord(
        long id,
        String name,
        Instant startsAt,
        Instant endsAt,
        String status) {
}
