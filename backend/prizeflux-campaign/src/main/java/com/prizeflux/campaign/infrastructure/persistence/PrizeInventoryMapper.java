package com.prizeflux.campaign.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 条件更新和余量读取的 MyBatis SQL 边界。 */
@Mapper
public interface PrizeInventoryMapper {
    int decrementIfAvailable(@Param("campaignId") long campaignId,
            @Param("configVersion") int configVersion, @Param("prizeId") long prizeId);

    Integer findRemaining(@Param("campaignId") long campaignId,
            @Param("configVersion") int configVersion, @Param("prizeId") long prizeId);
}
