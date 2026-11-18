package com.example.voucherflow.fulfillment;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.voucherflow.fulfillment.supplier.HttpSupplierClient;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;

/** 区分只读观察与修复取证，并统一保存供应商响应及其幂等身份。 */
@Service
public class SupplierEvidenceService {
    private final SupplierStore store;
    private final SupplierReceiptStore receipts;
    private final HttpSupplierClient client;
    private final FulfillmentProperties properties;
    private final TransactionTemplate tx;

    public SupplierEvidenceService(
            SupplierStore store,
            SupplierReceiptStore receipts,
            HttpSupplierClient client,
            FulfillmentProperties properties,
            TransactionTemplate tx) {
        this.store = store;
        this.receipts = receipts;
        this.client = client;
        this.properties = properties;
        this.tx = tx;
    }

    /** 对账和预览只观察供应商状态，不写入可改变本地订单的回执。 */
    public ReceiptEvidence observe(long orderId) {
        var order = store.find(orderId);
        // 已发现的矛盾证据必须留待核查，不能因这次查单恰好与本地一致就重新开放确认或释放操作。
        if (receipts.hasConflictingEvidence(orderId))
            return new ReceiptEvidence(0, "CONFLICT", null, null);
        try {
            var result = query(order);
            if (order.terminal()
                    && result.terminal()
                    && order.lastReceipt() != null
                    && !receipts.find(order.lastReceipt()).result().equals(result)) {
                return new ReceiptEvidence(0, "CONFLICT", result.resultVersion(), null);
            }
            return new ReceiptEvidence(
                    0, result.status(), result.resultVersion(), result.voucherRef());
        } catch (HttpSupplierClient.Unavailable | IllegalArgumentException ex) {
            return new ReceiptEvidence(0, "UNAVAILABLE", null, null);
        }
    }

    /** 执行修复前重新查单，证据保留为命令专用，等待修复事务应用。 */
    public ReceiptEvidence queryForRepair(long orderId) {
        var order = store.find(orderId);
        if (receipts.hasConflictingEvidence(orderId))
            throw new com.example.voucherflow.common.BusinessException(
                    com.example.voucherflow.common.ErrorCode.REPAIR_NOT_ALLOWED);
        SupplierProtocol.Result result;
        try {
            result = query(order);
        } catch (HttpSupplierClient.Unavailable | IllegalArgumentException ex) {
            throw new com.example.voucherflow.common.BusinessException(
                    com.example.voucherflow.common.ErrorCode.SYSTEM_BUSY);
        }
        long id = record(orderId, "repair:" + UUID.randomUUID(), "REPAIR_QUERY", null, result);
        return new ReceiptEvidence(
                id, result.status(), result.resultVersion(), result.voucherRef());
    }

    private SupplierProtocol.Result query(SupplierStore.Order order) {
        requireRoute(order);
        var result = client.call(order.request(), "QUERY");
        validateAssociation(order, result);
        return result;
    }

    public void requireRoute(SupplierStore.Order order) {
        // 订单保存的是受理时的供应商和契约，当前配置变更后不能把历史订单发送到另一套契约。
        if (!properties.isEnabled()
                || !properties.getSupplierId().equals(order.supplierId())
                || !properties.getContractVersion().equals(order.contractVersion())) {
            throw new IllegalArgumentException("Supplier contract is not configured");
        }
    }

    public static void validateAssociation(
            SupplierStore.Order order, SupplierProtocol.Result result) {
        result.validate();
        if (!order.requestNo().equals(result.requestNo())
                || !order.productCode().equals(result.productCode())
                || !order.subjectRef().equals(result.subjectRef()))
            throw new IllegalArgumentException("Supplier evidence does not match order");
    }

    /** 按供应商与回执键幂等存证；同一键的内容摘要必须一致。 */
    public long record(
            long orderId, String key, String source, String token, SupplierProtocol.Result result) {
        var order = store.find(orderId);
        requireRoute(order);
        validateAssociation(order, result);
        String hash = SupplierProtocol.resultHash(result);
        String state = "REPAIR_QUERY".equals(source) ? "HELD_FOR_COMMAND" : "PENDING";
        try {
            return tx.execute(status -> receipts.insert(
                    orderId, order.supplierId(), key, hash, source, token, result, state));
        } catch (DuplicateKeyException ex) {
            var previous = receipts.findByKey(order.supplierId(), key);
            if (!hash.equals(previous.hash())) {
                throw new IllegalArgumentException("Conflicting supplier receipt", ex);
            }
            return previous.id();
        }
    }
}
