package com.example.voucherflow.issuance;

import java.time.Instant;

import com.example.voucherflow.issuance.model.FulfillmentStatus;

/** 定义领取结果和订单详情，使用重放标记说明是否返回已有领取结果。 */
public final class FulfillmentDtos {
        private FulfillmentDtos() {
        }

        public record ClaimResult(
                        String orderId,
                        String campaignId,
                        FulfillmentStatus fulfillmentStatus,
                        String voucherId,
                        boolean replayed) {
        }

        public record OrderDetail(
                        String orderId,
                        String campaignId,
                        String campaignName,
                        FulfillmentStatus fulfillmentStatus,
                        String voucherId,
                        Instant expiresAt,
                        Instant createdAt,
                        Instant completedAt) {
        }
}
