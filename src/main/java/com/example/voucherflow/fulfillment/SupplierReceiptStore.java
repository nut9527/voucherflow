package com.example.voucherflow.fulfillment;

import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SupplierReceiptStore {
    public record Receipt(
            long id,
            long orderId,
            String source,
            String token,
            String applyState,
            SupplierProtocol.Result result) {}

    record StoredHash(long id, String hash) {}

    private final JdbcClient jdbc;

    public SupplierReceiptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insert(
            long orderId,
            String supplierId,
            String key,
            String hash,
            String source,
            String token,
            SupplierProtocol.Result result,
            String applyState) {
        var generated = new GeneratedKeyHolder();
        jdbc.sql(
                        """
                INSERT INTO supplier_receipts(order_id,supplier_id,receipt_key,payload_hash,source,request_no,
                    product_code,subject_ref,status,result_version,voucher_ref,execution_token,apply_state)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)
                .params(
                        orderId,
                        supplierId,
                        key,
                        hash,
                        source,
                        result.requestNo(),
                        result.productCode(),
                        result.subjectRef(),
                        result.status(),
                        result.resultVersion(),
                        result.voucherRef(),
                        token,
                        applyState)
                .update(generated, "id");
        return generated.getKey().longValue();
    }

    StoredHash findByKey(String supplierId, String key) {
        return jdbc.sql(
                        "SELECT id,payload_hash FROM supplier_receipts WHERE supplier_id=? AND receipt_key=?")
                .params(supplierId, key)
                .query((r, n) -> new StoredHash(r.getLong("id"), r.getString("payload_hash")))
                .single();
    }

    boolean hasConflictingEvidence(long orderId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM supplier_receipts WHERE order_id=? AND apply_state='CONFLICT')")
                .param(orderId)
                .query(Boolean.class)
                .single();
    }

    public Receipt find(long id) {
        return read(id, false);
    }

    public Receipt lock(long id) {
        return read(id, true);
    }

    private Receipt read(long id, boolean lock) {
        return jdbc.sql("SELECT * FROM supplier_receipts WHERE id=?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query(
                        (r, n) ->
                                new Receipt(
                                        r.getLong("id"),
                                        r.getLong("order_id"),
                                        r.getString("source"),
                                        r.getString("execution_token"),
                                        r.getString("apply_state"),
                                        new SupplierProtocol.Result(
                                                r.getString("request_no"),
                                                r.getString("product_code"),
                                                r.getString("subject_ref"),
                                                r.getString("status"),
                                                (Long) r.getObject("result_version"),
                                                r.getString("voucher_ref"))))
                .single();
    }

    public void setState(long id, String state) {
        jdbc.sql(
                        "UPDATE supplier_receipts SET apply_state=?,applied_at=UTC_TIMESTAMP(3) WHERE id=?")
                .params(state, id)
                .update();
    }

    public List<Long> pending(int limit) {
        return jdbc.sql(
                        """
                SELECT id FROM supplier_receipts WHERE apply_state='PENDING' AND next_apply_at<=UTC_TIMESTAMP(3)
                ORDER BY next_apply_at,id LIMIT ?
                """)
                .param(limit)
                .query(Long.class)
                .list();
    }

    public void defer(long id) {
        jdbc.sql(
                        """
                UPDATE supplier_receipts SET next_apply_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3))
                WHERE id=? AND apply_state='PENDING'
                """)
                .param(id)
                .update();
    }
}
