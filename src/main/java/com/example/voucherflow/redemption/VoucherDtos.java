package com.example.voucherflow.redemption;
import java.time.Instant;
/** 定义券详情与核销回执，响应中的重放标记用于识别重复核销。 */
public final class VoucherDtos {
    private VoucherDtos() {}
    public record Detail(String id, String orderId, String campaignId, String campaignName,
            String status, Instant issuedAt, Instant expiresAt, Instant usedAt) {}
    public record RedemptionReceipt(String id, String voucherId, String operatorId, Instant createdAt, boolean replayed) {}
}
