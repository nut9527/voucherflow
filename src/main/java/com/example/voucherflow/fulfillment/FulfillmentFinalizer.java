package com.example.voucherflow.fulfillment;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.inventory.InventoryService;
import com.example.voucherflow.issuance.mapper.OrderMapper;
import com.example.voucherflow.issuance.model.FulfillmentStatus;
import com.example.voucherflow.issuance.model.OrderRow;
import com.example.voucherflow.outbox.OutboxEventService;
import com.example.voucherflow.redemption.mapper.VoucherMapper;
import com.example.voucherflow.redemption.model.VoucherRow;

/** 统一确认履约终态，在同一事务中更新订单、库存流水、券和待发布事件。 */
@Service
public class FulfillmentFinalizer {
    private final CampaignMapper campaigns;
    private final OrderMapper orders;
    private final InventoryService inventory;
    private final VoucherMapper vouchers;
    private final FulfillmentEventFactory events;
    private final OutboxEventService outbox;
    private final SupplierStore supplier;
    private final SupplierReceiptStore receipts;

    public FulfillmentFinalizer(
            CampaignMapper campaigns,
            OrderMapper orders,
            InventoryService inventory,
            VoucherMapper vouchers,
            FulfillmentEventFactory events,
            OutboxEventService outbox,
            SupplierStore supplier,
            SupplierReceiptStore receipts) {
        this.campaigns = campaigns;
        this.orders = orders;
        this.inventory = inventory;
        this.vouchers = vouchers;
        this.events = events;
        this.outbox = outbox;
        this.supplier = supplier;
        this.receipts = receipts;
    }

    /** 本地发放复用领取事务，重复确认返回已生成的券。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public FinalizationResult confirmLocalInCurrentTransaction(long campaignId, long orderId) {
        campaigns.lockById(campaignId).orElseThrow();
        var order = orders.lockById(orderId).orElseThrow();
        if (order.getCampaignId() != campaignId
                || order.getFulfillmentMode() != FulfillmentMode.LOCAL) {
            throw new IllegalStateException("Order is not a local fulfillment for this campaign");
        }
        if (order.getStatus() == FulfillmentStatus.FULFILLED) {
            long voucherId = vouchers.findByOrderId(orderId).orElseThrow().getId();
            return new FinalizationResult(orderId, order.getStatus(), voucherId, true);
        }
        if (order.getStatus().isTerminal()) {
            throw new IllegalStateException("Cannot confirm a failed order");
        }
        var voucher = confirm(order, null);
        return new FinalizationResult(orderId, FulfillmentStatus.FULFILLED, voucher.getId(), false);
    }

    /** 修复命令在自身事务中应用专属查询证据，并核对请求携带的订单版本。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public EvidenceApplyResult applyReceiptInCurrentTransaction(
            long orderId, long receiptId, String commandId, long expectedRevision) {
        return apply(orderId, receiptId, commandId, expectedRevision);
    }

    /** 为执行器和回执补偿提供带事务的终态确认入口。 */
    @Transactional
    public EvidenceApplyResult applyReceipt(long receiptId) {
        var receipt = receipts.find(receiptId);
        return apply(receipt.orderId(), receiptId, null, null);
    }

    private EvidenceApplyResult apply(
            long orderId, long receiptId, String commandId, Long expectedRevision) {
        var snapshot = supplier.find(orderId);
        // 按活动、订单、回执的顺序加锁，与库存变更保持相同的锁顺序。
        campaigns.lockById(snapshot.campaignId()).orElseThrow();
        var order = supplier.lock(orderId);
        var receipt = receipts.lock(receiptId);
        if (receipt.orderId() != orderId) {
            throw new IllegalArgumentException("Receipt belongs to another order");
        }
        // 远程取证期间订单可能已被回调或执行器推进，持锁后再比较版本才能识别过时的修复请求。
        if (expectedRevision != null && expectedRevision != order.revision()) {
            throw new BusinessException(ErrorCode.REVISION_CONFLICT);
        }
        if ("APPLIED".equals(receipt.applyState()) || "IGNORED".equals(receipt.applyState())) {
            return new EvidenceApplyResult("REPLAY", order.revision());
        }
        if (commandId == null && "HELD_FOR_COMMAND".equals(receipt.applyState())) {
            throw new IllegalArgumentException("Repair evidence requires a command");
        }
        if (commandId != null
                && (!"REPAIR_QUERY".equals(receipt.source())
                        || !"HELD_FOR_COMMAND".equals(receipt.applyState()))) {
            throw new IllegalArgumentException("Command requires held query evidence");
        }
        SupplierEvidenceService.validateAssociation(order, receipt.result());
        return switch (assess(order, receipt)) {
            case APPLY -> applyAccepted(order, receipt, commandId);
            case STALE_EXECUTOR -> recordVerdict(order, receipt, "STALE", "STALE_EXECUTOR");
            case IGNORED_OLD_VERSION ->
                recordVerdict(order, receipt, "IGNORED", "IGNORED_OLD_VERSION");
            case CONFLICT -> recordVerdict(order, receipt, "CONFLICT", "CONFLICT");
            case REPLAY -> recordVerdict(order, receipt, "APPLIED", "REPLAY");
        };
    }

    private enum Verdict {
        APPLY,
        STALE_EXECUTOR,
        IGNORED_OLD_VERSION,
        CONFLICT,
        REPLAY
    }

    /** 依次判断执行租约、供应商结果版本和本地终态是否允许接受当前回执。 */
    private Verdict assess(SupplierStore.Order order, SupplierReceiptStore.Receipt receipt) {
        // 执行器回执只代表某次租约下的结果；租约已被接管时保留证据，但不允许旧执行器推进订单。
        if ("WORKER".equals(receipt.source())
                && (!"RUNNING".equals(order.jobState())
                        || !Objects.equals(receipt.token(), order.token())
                        || !supplier.now().isBefore(order.leaseUntil()))) {
            return Verdict.STALE_EXECUTOR;
        }
        var result = receipt.result();
        if (order.lastVersion() != null && result.resultVersion() != null) {
            var previous = receipts.find(order.lastReceipt()).result();
            // 终态内容冲突优先于版本先后判定，旧版本也可能揭示相互矛盾的发放结果。
            if (previous.terminal()
                    && result.terminal()
                    && (!previous.status().equals(result.status())
                            || !Objects.equals(previous.voucherRef(), result.voucherRef()))) {
                return Verdict.CONFLICT;
            }
            if (result.resultVersion() < order.lastVersion()) {
                return Verdict.IGNORED_OLD_VERSION;
            }
            // 同一供应商版本只能对应同一结果，已接受的终态也不能被后续不同结果替换。
            if ((result.resultVersion().equals(order.lastVersion()) && !previous.equals(result))
                    || (previous.terminal() && !previous.equals(result))) {
                return Verdict.CONFLICT;
            }
        }
        // 已完成订单只接受与本地终态一致的重放，避免再次确认或释放库存。
        if (order.terminal()) {
            boolean matches = "FULFILLED".equals(order.status()) && "SUCCEEDED".equals(result.status())
                    || "FAILED".equals(order.status())
                            && "REJECTED".equals(result.status());
            return matches ? Verdict.REPLAY : Verdict.CONFLICT;
        }
        return Verdict.APPLY;
    }

    private EvidenceApplyResult recordVerdict(
            SupplierStore.Order order,
            SupplierReceiptStore.Receipt receipt,
            String state,
            String disposition) {
        receipts.setState(receipt.id(), state);
        return new EvidenceApplyResult(disposition, order.revision());
    }

    private EvidenceApplyResult applyAccepted(
            SupplierStore.Order order, SupplierReceiptStore.Receipt receipt, String commandId) {
        var result = receipt.result();
        if (result.resultVersion() != null) {
            supplier.acceptVersion(order.id(), receipt.id(), result.resultVersion());
        }
        // 处理中或未查到记录仅更新证据，不确认或释放已经预留的库存。
        // 未查到记录不等于明确拒绝，供应商仍可能已受理或正在处理，提前释放会使库存与实际发放脱节。
        if (!result.terminal()) {
            receipts.setState(receipt.id(), "APPLIED");
            return new EvidenceApplyResult("PROGRESS", supplier.find(order.id()).revision());
        }
        var row = orders.lockById(order.id()).orElseThrow();
        if ("SUCCEEDED".equals(result.status())) {
            confirm(row, result.voucherRef());
            supplier.markLateFulfillment(order.id());
        } else {
            inventory.releaseInCurrentTransaction(order.campaignId(), order.id());
            supplier.markFailed(order.id());
            outbox.appendInCurrentTransaction(events.fulfillmentFailed(row, supplier.now()));
        }
        supplier.completeWithReceipt(order.id(), receipt.id(), commandId);
        receipts.setState(receipt.id(), "APPLIED");
        return new EvidenceApplyResult("APPLIED", supplier.find(order.id()).revision());
    }

    /** 库存确认、订单终态、券和发券事件由同一事务提交。 */
    private VoucherRow confirm(OrderRow order, String supplierVoucherRef) {
        inventory.confirmInCurrentTransaction(order.getCampaignId(), order.getId());
        if (orders.markFulfilled(order.getId()) != 1) {
            throw new IllegalStateException("Order confirmation failed");
        }
        var voucher = new VoucherRow();
        voucher.setOrderId(order.getId());
        voucher.setCampaignId(order.getCampaignId());
        voucher.setUserId(order.getUserId());
        voucher.setExpiresAt(order.getExpiresAt());
        voucher.setSupplierVoucherRef(supplierVoucherRef);
        vouchers.insert(voucher);
        voucher = vouchers.findById(voucher.getId()).orElseThrow();
        outbox.appendInCurrentTransaction(events.voucherIssued(order, voucher));
        return voucher;
    }
}
