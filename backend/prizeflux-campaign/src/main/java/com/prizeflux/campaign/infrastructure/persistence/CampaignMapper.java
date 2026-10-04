package com.prizeflux.campaign.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** MyBatis数据库操作边界；SQL集中保存在同名XML中。 */
@Mapper
public interface CampaignMapper {

    int insert(@Param("record") CampaignPersistenceRecord record);

    int reserveManualId(@Param("id") long id);

    @Options(useGeneratedKeys = true, keyProperty = "record.id", keyColumn = "id")
    int reserveGeneratedId(@Param("record") CampaignIdRecord record);

    int insertOwned(@Param("record") CampaignPersistenceRecord record,
            @Param("ownerUserId") long ownerUserId);

    CampaignPersistenceRecord findById(@Param("id") long id);

    List<CampaignPersistenceRecord> findAvailable(
            @Param("now") Instant now, @Param("beforeId") Long beforeId, @Param("limit") int limit);

    List<CampaignPersistenceRecord> findAll(
            @Param("beforeId") Long beforeId, @Param("limit") int limit);

    List<CampaignPersistenceRecord> findOwned(
            @Param("ownerUserId") long ownerUserId, @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    CampaignPersistenceRecord findOwnedById(
            @Param("id") long id, @Param("ownerUserId") long ownerUserId);

    int updateOwnedDetails(@Param("id") long id, @Param("ownerUserId") long ownerUserId,
            @Param("name") String name, @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt);

    int submitOwnedForReview(@Param("id") long id, @Param("ownerUserId") long ownerUserId);

    int updateStatus(
            @Param("id") long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus);
}
