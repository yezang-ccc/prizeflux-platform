package com.prizeflux.campaign.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis数据库操作边界；SQL集中保存在同名XML中。 */
@Mapper
public interface CampaignMapper {

    int insert(@Param("record") CampaignPersistenceRecord record);

    CampaignPersistenceRecord findById(@Param("id") long id);

    int updateStatus(
            @Param("id") long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus);
}
