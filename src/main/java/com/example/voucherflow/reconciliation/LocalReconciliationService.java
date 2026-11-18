package com.example.voucherflow.reconciliation;

import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.reconciliation.mapper.LocalStatsMapper;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 在一致性快照内核对库存、订单、流水、券与核销记录的守恒关系。 */
@Service
public class LocalReconciliationService {
    private final CurrentUser currentUser;
    private final CampaignMapper campaigns;
    private final LocalStatsMapper stats;
    private final TransactionTemplate snapshot;

    public LocalReconciliationService(
            CurrentUser currentUser,
            CampaignMapper campaigns,
            LocalStatsMapper stats,
            PlatformTransactionManager transactionManager) {
        this.currentUser = currentUser;
        this.campaigns = campaigns;
        this.stats = stats;
        // 多张表的统计必须来自同一可重复读快照，避免并发领取产生虚假的账目差异。
        snapshot = new TransactionTemplate(transactionManager);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public LocalStats checkCampaign(long campaignId) {
        currentUser.requireAdminId();
        return inspect(campaignId);
    }

    public LocalStats inspect(long campaignId) {
        return snapshot.execute(status -> checkCampaignInternal(campaignId));
    }

    private LocalStats checkCampaignInternal(long campaignId) {
        var campaign =
                campaigns
                        .findById(campaignId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var observedAt = stats.databaseNow();
        var orders = stats.orderCounts(campaignId);
        var ledger = stats.ledgerTotals(campaignId);
        var vouchers = stats.voucherCounts(campaignId);
        long redemptions = stats.redemptionCount(campaignId);
        long broken = stats.brokenLinks(campaignId);
        long total = campaign.getTotalStock(),
                available = campaign.getAvailableStock(),
                reserved = campaign.getReservedStock(),
                issued = campaign.getIssuedStock();
        var differences = new LinkedHashMap<String, Long>();
        // 每项差值都应为零，分别覆盖库存守恒、业务数量和流水累计变化。
        differences.put("stockBalance", available + reserved + issued - total);
        // UNKNOWN 仅表示远端结果待确认，和 PENDING 一样仍占用预留，不能计入可用库存。
        differences.put("reservedOrders", reserved - orders.pending() - orders.unknown());
        differences.put("issuedOrders", issued - orders.fulfilled());
        differences.put("issuedVouchers", issued - vouchers.vouchers());
        differences.put(
                "availableLedger", available - (total - ledger.reserves() + ledger.releases()));
        differences.put(
                "reservedLedger",
                reserved - (ledger.reserves() - ledger.confirms() - ledger.releases()));
        differences.put("issuedLedger", issued - ledger.confirms());
        differences.put("usedReceipts", vouchers.used() - redemptions);
        // 流水条数推导库存之外，再按实际增量复算，检查流水的数值是否与业务动作一致。
        differences.put("availableDelta", available - total - ledger.availableDelta());
        differences.put("reservedDelta", reserved - ledger.reservedDelta());
        differences.put("issuedDelta", issued - ledger.issuedDelta());
        // 总量相等仍可能存在券、回执关联到错误订单的情况，必须同时通过逐笔关联检查。
        boolean consistent =
                broken == 0 && differences.values().stream().allMatch(value -> value == 0);
        return new LocalStats(
                Long.toString(campaignId),
                total,
                available,
                reserved,
                issued,
                orders.pending(),
                orders.unknown(),
                orders.fulfilled(),
                orders.failed(),
                vouchers.vouchers(),
                vouchers.used(),
                redemptions,
                ledger.reserves(),
                ledger.confirms(),
                ledger.releases(),
                broken,
                differences,
                consistent,
                observedAt);
    }
}
