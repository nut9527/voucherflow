package com.example.voucherflow.fulfillment;

import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 持久化供应商履约订单、执行状态和回执，统一使用数据库时间判断租约。 */
@Repository
public class SupplierStore {
    private final JdbcTemplate jdbc;
    public SupplierStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }
    public record Order(long id, long campaignId, long userId, String requestNo, String supplierId,
                        String contractVersion, String productCode, String subjectRef, String status,
                        long revision, Instant deadline, String jobState, String token, Instant leaseUntil,
                        String nextAction, int attemptCount, int manualCredits, Long lastVersion, Long lastReceipt, Instant createdAt) {
        public SupplierProtocol.IssueRequest request() { return new SupplierProtocol.IssueRequest(requestNo, productCode, subjectRef); }
        public boolean terminal() { return "FULFILLED".equals(status) || "FAILED".equals(status); }
    }
    public record Receipt(long id, long orderId, String source, String token, String applyState, SupplierProtocol.Result result) {}
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        return timestamp == null ? null : timestamp.toInstant();
    }
    private Order order(ResultSet r, int n) throws SQLException {
        return new Order(r.getLong("id"), r.getLong("campaign_id"), r.getLong("user_id"), r.getString("request_no"),
                r.getString("supplier_id"), r.getString("contract_version"), r.getString("product_code"), r.getString("subject_ref"),
                r.getString("status"), r.getLong("revision"), instant(r,"idempotency_deadline_at"), r.getString("job_state"),
                r.getString("execution_token"), instant(r,"lease_until"), r.getString("next_action"), r.getInt("attempt_count"),
                r.getInt("manual_query_credits"), (Long)r.getObject("last_result_version"), (Long)r.getObject("last_accepted_receipt_id"),instant(r,"created_at"));
    }
    public Order find(long id) { return jdbc.queryForObject("SELECT * FROM fulfillment_orders WHERE id=?", this::order, id); }
    public Order byRequest(String requestNo) { return jdbc.queryForObject("SELECT * FROM fulfillment_orders WHERE request_no=?", this::order, requestNo); }
    public boolean hasConflictingEvidence(long orderId) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM supplier_receipts WHERE order_id=? AND apply_state='CONFLICT')",Boolean.class,orderId);
    }
    public Order lock(long id) { return jdbc.queryForObject("SELECT * FROM fulfillment_orders WHERE id=? FOR UPDATE", this::order, id); }
    public Instant now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", (r,n) -> instant(r,1)); }
    private static Instant instant(ResultSet r, int column) throws SQLException {
        return r.getTimestamp(column, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))).toInstant();
    }
    /** 跳过其他执行器已锁定的任务，供调用方在当前事务内领取。 */
    public List<Order> due(int limit) {
        return jdbc.query("SELECT * FROM fulfillment_orders WHERE job_state='READY' AND next_run_at<=UTC_TIMESTAMP(3) ORDER BY next_run_at,id LIMIT ? FOR UPDATE SKIP LOCKED", this::order, limit);
    }
    public Receipt receipt(long id) {
        return receipt(id,false);
    }
    public Receipt lockReceipt(long id) { return receipt(id,true); }
    private Receipt receipt(long id,boolean lock) {
        return jdbc.queryForObject("SELECT * FROM supplier_receipts WHERE id=?"+(lock?" FOR UPDATE":""), (r,n) -> new Receipt(r.getLong("id"),
                r.getLong("order_id"), r.getString("source"), r.getString("execution_token"), r.getString("apply_state"),
                new SupplierProtocol.Result(r.getString("request_no"),r.getString("product_code"),r.getString("subject_ref"),
                        r.getString("status"),(Long)r.getObject("result_version"),r.getString("voucher_ref"))),id);
    }
    public void receiptState(long id,String state) {
        jdbc.update("UPDATE supplier_receipts SET apply_state=?,applied_at=UTC_TIMESTAMP(3) WHERE id=?",state,id);
    }

    // 此处只扫描候选编号；多实例可能读到同一回执，实际应用时通过回执的行锁及应用状态消除重复。
    public List<Long> pendingReceipts(int limit) {
        return jdbc.queryForList("""
                SELECT id FROM supplier_receipts
                WHERE apply_state='PENDING' AND next_apply_at<=UTC_TIMESTAMP(3)
                ORDER BY next_apply_at,id LIMIT ?
                """, Long.class, limit);
    }

    /** 将失败回执延后处理，让其他已到期回执获得执行机会。 */
    public void deferReceipt(long id) {
        jdbc.update("""
                UPDATE supplier_receipts SET next_apply_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3))
                WHERE id=? AND apply_state='PENDING'
                """, id);
    }

    // 供应商结果版本与本地订单版本含义不同：接受新证据也推进本地版本，让旧修复请求能够识别变化。
    public void acceptVersion(long orderId, long receiptId, long version) {
        jdbc.update("""
                UPDATE fulfillment_orders
                SET last_result_version=?,last_accepted_receipt_id=?,revision=revision+1 WHERE id=?
                """, version, receiptId, orderId);
    }

    // 券已到期也不能否认供应商发放成功的事实，保留成功结果并标记迟到，交由对账发现。
    public void markLateFulfillment(long orderId) {
        jdbc.update("""
                UPDATE fulfillment_orders SET late_fulfillment=(expires_at<=UTC_TIMESTAMP(3)) WHERE id=?
                """, orderId);
    }

    public void markFailed(long orderId) {
        jdbc.update("""
                UPDATE fulfillment_orders
                SET status='FAILED',completed_at=UTC_TIMESTAMP(3),revision=revision+1 WHERE id=?
                """, orderId);
    }

    /** 为库存终态流水关联来源证据和修复命令，并结束持久履约任务。 */
    public void completeWithReceipt(long orderId, long receiptId, String commandId) {
        jdbc.update("""
                UPDATE stock_ledger SET source_receipt_id=?,repair_command_id=?
                WHERE order_id=? AND entry_type IN ('CONFIRM','RELEASE')
                """, receiptId, commandId, orderId);
        jdbc.update("""
                UPDATE fulfillment_orders SET job_state='DONE',execution_token=NULL,lease_until=NULL WHERE id=?
                """, orderId);
    }
}
