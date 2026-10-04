package com.prizeflux.campaign.infrastructure.persistence;

/** 可变数据库行对象；MyBatis 将 AUTO_INCREMENT 主键写回 id。 */
public final class CampaignIdRecord {
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
