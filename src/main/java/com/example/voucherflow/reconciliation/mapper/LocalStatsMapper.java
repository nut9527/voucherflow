package com.example.voucherflow.reconciliation.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;

/** 汇总活动账目并查找跨表关联异常，供本地一致性核对使用。 */
@Mapper
public interface LocalStatsMapper {
    OrderCounts orderCounts(long campaignId);

    LedgerTotals ledgerTotals(long campaignId);

    VoucherCounts voucherCounts(long campaignId);

    long redemptionCount(long campaignId);

    long brokenLinks(long campaignId);

    Instant databaseNow();

    record OrderCounts(long pending, long unknown, long fulfilled, long failed) {}

    record LedgerTotals(
            long reserves,
            long confirms,
            long releases,
            long availableDelta,
            long reservedDelta,
            long issuedDelta) {}

    record VoucherCounts(long vouchers, long used) {}
}
