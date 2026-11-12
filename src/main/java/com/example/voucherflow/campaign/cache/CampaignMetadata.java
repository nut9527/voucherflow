package com.example.voucherflow.campaign.cache;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.model.CampaignPhase;
import com.example.voucherflow.campaign.model.CampaignRow;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import java.time.Clock;
import java.time.Instant;

/** 保存可公开缓存的活动元数据，领取阶段在读取时按当前时间计算。 */
public record CampaignMetadata(long id, String name, String description, int totalStock,
        CampaignStatus status, Instant startAt, Instant endAt, Instant redeemEndAt, FulfillmentMode fulfillmentMode) {
    public CampaignMetadata {
        if (id < 1 || name == null || description == null || totalStock < 1 || status == null
                || status == CampaignStatus.DRAFT || startAt == null || endAt == null || redeemEndAt == null
                || fulfillmentMode == null || !startAt.isBefore(endAt) || endAt.isAfter(redeemEndAt)) {
            throw new IllegalArgumentException("Invalid public campaign metadata");
        }
    }

    public static CampaignMetadata fromRow(CampaignRow row) {
        return new CampaignMetadata(row.getId(), row.getName(), row.getDescription(), row.getTotalStock(),
                row.getStatus(), row.getStartAt(), row.getEndAt(), row.getRedeemEndAt(), row.getFulfillmentMode());
    }

    /** 每次读取重新计算阶段，让缓存中的活动可以随时间进入领取期或结束。 */
    public CampaignDtos.Detail toDetail(Clock clock) {
        Instant now = clock.instant();
        CampaignPhase phase = status == CampaignStatus.DISABLED ? CampaignPhase.DISABLED
                : status == CampaignStatus.ENDED || !now.isBefore(endAt) ? CampaignPhase.ENDED
                : now.isBefore(startAt) ? CampaignPhase.NOT_STARTED : CampaignPhase.CLAIMABLE;
        return new CampaignDtos.Detail(Long.toString(id), name, totalStock, status, phase,
                startAt, endAt, description, redeemEndAt, fulfillmentMode);
    }
}
