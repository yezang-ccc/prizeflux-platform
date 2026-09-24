package com.prizeflux.campaign.domain.model;

/** 活动管理状态；发布不表示当前已经进入活动时间窗口。 */
public enum CampaignStatus {
    /** 草稿：尚未发布，不允许参与。 */
    DRAFT,
    /** 已发布：还需结合时间窗口判断能否参与。 */
    PUBLISHED,
    /** 已关闭：终止状态，不允许重新发布。 */
    CLOSED
}
