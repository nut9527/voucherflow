package com.example.voucherflow.reconciliation;
import java.time.Instant;
import java.util.Map;
/** 活动本地对账结果，包含各类账目数量、差值和观察时间。 */
public record LocalStats(String campaignId, long totalStock, long availableStock, long reservedStock, long issuedStock,
        long pendingCount, long unknownCount, long fulfilledCount, long failedCount,
        long voucherCount, long usedCount, long redemptionCount, long reserveCount, long confirmCount, long releaseCount,
        long brokenLinks, Map<String, Long> differences, boolean consistent, Instant observedAt) {}
