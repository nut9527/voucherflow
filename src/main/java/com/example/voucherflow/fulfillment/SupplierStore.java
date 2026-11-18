package com.example.voucherflow.fulfillment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;

/** 读取供应商履约订单，并记录供应商版本和订单终态。 */
@Repository
public class SupplierStore {
    private final JdbcTemplate jdbc;

    public SupplierStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Order(
            long id,
            long campaignId,
            long userId,
            String requestNo,
            String supplierId,
            String contractVersion,
            String productCode,
            String subjectRef,
            String status,
            long revision,
            Instant deadline,
            String jobState,
            String token,
            Instant leaseUntil,
            String nextAction,
            int attemptCount,
            int manualCredits,
            Long lastVersion,
            Long lastReceipt,
            Instant createdAt) {
        public SupplierProtocol.IssueRequest request() {
            return new SupplierProtocol.IssueRequest(requestNo, productCode, subjectRef);
        }

        public boolean terminal() {
            return "FULFILLED".equals(status) || "FAILED".equals(status);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(
                column,
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        return timestamp == null ? null : timestamp.toInstant();
    }

    static Order order(ResultSet r, int n) throws SQLException {
        return new Order(
                r.getLong("id"),
                r.getLong("campaign_id"),
                r.getLong("user_id"),
                r.getString("request_no"),
                r.getString("supplier_id"),
                r.getString("contract_version"),
                r.getString("product_code"),
                r.getString("subject_ref"),
                r.getString("status"),
                r.getLong("revision"),
                instant(r, "idempotency_deadline_at"),
                r.getString("job_state"),
                r.getString("execution_token"),
                instant(r, "lease_until"),
                r.getString("next_action"),
                r.getInt("attempt_count"),
                r.getInt("manual_query_credits"),
                (Long) r.getObject("last_result_version"),
                (Long) r.getObject("last_accepted_receipt_id"),
                instant(r, "created_at"));
    }

    public Order find(long id) {
        return jdbc.queryForObject("SELECT * FROM fulfillment_orders WHERE id=?", SupplierStore::order, id);
    }

    public Order byRequest(String requestNo) {
        return jdbc.queryForObject(
                "SELECT * FROM fulfillment_orders WHERE request_no=?", SupplierStore::order, requestNo);
    }

    public Order lock(long id) {
        return jdbc.queryForObject(
                "SELECT * FROM fulfillment_orders WHERE id=? FOR UPDATE", SupplierStore::order, id);
    }

    public Instant now() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", (r, n) -> instant(r, 1));
    }

    private static Instant instant(ResultSet r, int column) throws SQLException {
        return r.getTimestamp(
                column,
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")))
                .toInstant();
    }

    // 供应商结果版本与本地订单版本含义不同：接受新证据也推进本地版本，让旧修复请求能够识别变化。
    public void acceptVersion(long orderId, long receiptId, long version) {
        jdbc.update(
                """
                        UPDATE fulfillment_orders
                        SET last_result_version=?,last_accepted_receipt_id=?,revision=revision+1 WHERE id=?
                        """,
                version,
                receiptId,
                orderId);
    }

    // 券已到期也不能否认供应商发放成功的事实，保留成功结果并标记迟到，交由对账发现。
    public void markLateFulfillment(long orderId) {
        jdbc.update(
                """
                        UPDATE fulfillment_orders SET late_fulfillment=(expires_at<=UTC_TIMESTAMP(3)) WHERE id=?
                        """,
                orderId);
    }

    public void markFailed(long orderId) {
        jdbc.update(
                """
                        UPDATE fulfillment_orders
                        SET status='FAILED',completed_at=UTC_TIMESTAMP(3),revision=revision+1 WHERE id=?
                        """,
                orderId);
    }

    /** 为库存终态流水关联来源证据和修复命令，并结束持久履约任务。 */
    public void completeWithReceipt(long orderId, long receiptId, String commandId) {
        jdbc.update(
                """
                        UPDATE stock_ledger SET source_receipt_id=?,repair_command_id=?
                        WHERE order_id=? AND entry_type IN ('CONFIRM','RELEASE')
                        """,
                receiptId,
                commandId,
                orderId);
        jdbc.update(
                """
                        UPDATE fulfillment_orders SET job_state='DONE',execution_token=NULL,lease_until=NULL WHERE id=?
                        """,
                orderId);
    }
}
