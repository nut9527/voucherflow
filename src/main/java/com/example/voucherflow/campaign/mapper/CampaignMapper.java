package com.example.voucherflow.campaign.mapper;

import com.example.voucherflow.campaign.model.CampaignRow;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 映射活动查询与状态变更，提供业务事务使用的活动行锁。 */
@Mapper
public interface CampaignMapper {
    void insertDraft(CampaignRow row);
    int updateDraft(@Param("id") long id, @Param("params") UpdateDraftParams params);
    Optional<CampaignRow> findById(@Param("id") long id);
    Optional<CampaignRow> lockById(@Param("id") long id);
    Optional<CampaignRow> findPublicById(@Param("id") long id);
    int publishDraft(@Param("id") long id);
    int disableActive(@Param("id") long id);
    List<CampaignRow> findPublicPage(@Param("afterId") long afterId, @Param("limit") int limit);
    List<CampaignRow> findAdminPage(@Param("afterId") long afterId, @Param("limit") int limit);

    record UpdateDraftParams(String name, String description, int totalStock, Instant startAt,
            Instant endAt, Instant redeemEndAt, FulfillmentMode fulfillmentMode, String supplierId,
            String productCode, String contractVersion) {}
}

