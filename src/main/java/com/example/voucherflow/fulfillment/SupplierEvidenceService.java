package com.example.voucherflow.fulfillment;

import com.example.voucherflow.fulfillment.supplier.HttpSupplierClient;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 区分只读观察与修复取证，并统一保存供应商响应及其幂等身份。 */
@Service
public class SupplierEvidenceService {
    private final SupplierStore store;
    private final HttpSupplierClient client;
    private final FulfillmentProperties properties;
    private final TransactionTemplate tx;
    public SupplierEvidenceService(SupplierStore store, HttpSupplierClient client, FulfillmentProperties properties, TransactionTemplate tx) {
        this.store=store; this.client=client; this.properties=properties; this.tx=tx;
    }
    /** 对账和预览只观察供应商状态，不写入可改变本地订单的回执。 */
    public ReceiptEvidence observe(long orderId) {
        var order = store.find(orderId);
        // 已发现的矛盾证据必须留待核查，不能因这次查单恰好与本地一致就重新开放确认或释放操作。
        if(store.hasConflictingEvidence(orderId)) return new ReceiptEvidence(0,"CONFLICT",null,null);
        try {
            var result = query(order);
            if(order.terminal() && result.terminal() && order.lastReceipt()!=null
                    && !store.receipt(order.lastReceipt()).result().equals(result)) {
                return new ReceiptEvidence(0,"CONFLICT",result.resultVersion(),null);
            }
            return new ReceiptEvidence(0,result.status(),result.resultVersion(),result.voucherRef());
        } catch(HttpSupplierClient.Unavailable | IllegalArgumentException ex) {
            return new ReceiptEvidence(0,"UNAVAILABLE",null,null);
        }
    }
    /** 执行修复前重新查单，证据保留为命令专用，等待修复事务应用。 */
    public ReceiptEvidence queryForRepair(long orderId) {
        var order = store.find(orderId);
        if(store.hasConflictingEvidence(orderId)) throw new com.example.voucherflow.common.BusinessException(com.example.voucherflow.common.ErrorCode.REPAIR_NOT_ALLOWED);
        SupplierProtocol.Result result;
        try { result=query(order); }
        catch(HttpSupplierClient.Unavailable | IllegalArgumentException ex) {
            throw new com.example.voucherflow.common.BusinessException(com.example.voucherflow.common.ErrorCode.SYSTEM_BUSY);
        }
        long id = record(orderId,"repair:"+UUID.randomUUID(),"REPAIR_QUERY",null,result);
        return new ReceiptEvidence(id,result.status(),result.resultVersion(),result.voucherRef());
    }
    private SupplierProtocol.Result query(SupplierStore.Order order) {
        requireRoute(order);
        var result=client.call(order.request(),"QUERY");
        validateAssociation(order,result);
        return result;
    }
    public void requireRoute(SupplierStore.Order order) {
        // 订单保存的是受理时的供应商和契约，当前配置变更后不能把历史订单发送到另一套契约。
        if (!properties.isEnabled() || !properties.getSupplierId().equals(order.supplierId())
                || !properties.getContractVersion().equals(order.contractVersion())) {
            throw new IllegalArgumentException("Supplier contract is not configured");
        }
    }
    public static void validateAssociation(SupplierStore.Order order,SupplierProtocol.Result result) {
        result.validate();
        if (!order.requestNo().equals(result.requestNo()) || !order.productCode().equals(result.productCode())
                || !order.subjectRef().equals(result.subjectRef())) throw new IllegalArgumentException("Supplier evidence does not match order");
    }
    /** 按供应商与回执键幂等存证；同一键的内容摘要必须一致。 */
    public long record(long orderId,String key,String source,String token,SupplierProtocol.Result result) {
        var order=store.find(orderId);
        requireRoute(order);
        validateAssociation(order,result);
        String hash=SupplierProtocol.resultHash(result);
        try {
            return tx.execute(status -> {
                var holder=new GeneratedKeyHolder();
                store.jdbc().update(connection -> {
                    PreparedStatement statement=connection.prepareStatement("INSERT INTO supplier_receipts(order_id,supplier_id,receipt_key,payload_hash,source,request_no,product_code,subject_ref,status,result_version,voucher_ref,execution_token,apply_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
                    // 修复证据不能被后台回执扫描抢先应用，由对应命令完成版本校验后使用。
                    Object[] values={orderId,order.supplierId(),key,hash,source,result.requestNo(),result.productCode(),result.subjectRef(),result.status(),result.resultVersion(),result.voucherRef(),token,"REPAIR_QUERY".equals(source)?"HELD_FOR_COMMAND":"PENDING"};
                    for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
                    return statement;
                },holder);
                return holder.getKey().longValue();
            });
        } catch(DuplicateKeyException ex) {
            // 插入事务退出后读取并发请求已写入的记录，使相同回执键最终返回同一个编号。
            var previous=store.jdbc().queryForMap("SELECT id,payload_hash FROM supplier_receipts WHERE supplier_id=? AND receipt_key=?",order.supplierId(),key);
            if(!hash.equals(previous.get("payload_hash"))) throw new IllegalArgumentException("Conflicting supplier receipt",ex);
            return ((Number)previous.get("id")).longValue();
        }
    }
}
