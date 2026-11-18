package com.example.voucherflow.issuance.model;

import java.time.Instant;

/** 订单查询视图，汇总活动名称、履约状态和发放成功后关联的券。 */
public record OrderView(
        long orderId,
        long campaignId,
        long userId,
        String campaignName,
        FulfillmentStatus status,
        Long voucherId,
        Instant expiresAt,
        Instant createdAt,
        Instant completedAt) {}
