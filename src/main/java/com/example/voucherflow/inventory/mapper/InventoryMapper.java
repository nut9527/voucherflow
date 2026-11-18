package com.example.voucherflow.inventory.mapper;

import com.example.voucherflow.campaign.model.CampaignStatus;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

/** 通过条件更新预留、确认或释放库存，并查询预留失败时的活动状态。 */
@Mapper
public interface InventoryMapper {
    int reserve(long campaignId);

    int confirm(long campaignId);

    int release(long campaignId);

    Optional<Availability> diagnoseAvailability(long campaignId);

    record Availability(
            CampaignStatus status,
            Instant startAt,
            Instant endAt,
            int availableStock,
            Instant observedAt) {}
}
