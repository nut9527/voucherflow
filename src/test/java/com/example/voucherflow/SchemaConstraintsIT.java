package com.example.voucherflow;

import com.example.voucherflow.support.MySqlIntegrationTest;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 直接写入数据库，验证库存守恒、唯一业务关系和终态字段约束。 */
class SchemaConstraintsIT extends MySqlIntegrationTest {
    @Test
    void rejectsNegativeStockEvenWhenTotalStillBalances() {
        long campaign = campaign();
        rejects("ck_campaign_stock_nonnegative", () -> jdbc.update(
                "UPDATE campaigns SET available_stock=-1,reserved_stock=11 WHERE id=?", campaign));
        assertThat(jdbc.queryForObject("SELECT available_stock FROM campaigns WHERE id=?", Integer.class, campaign)).isEqualTo(10);
    }

    @Test
    void rejectsBrokenStockSum() {
        long campaign = campaign();
        rejects("ck_campaign_stock_sum", () -> jdbc.update(
                "UPDATE campaigns SET available_stock=9 WHERE id=?", campaign));
        assertThat(jdbc.queryForObject("SELECT available_stock FROM campaigns WHERE id=?", Integer.class, campaign)).isEqualTo(10);
    }

    @Test
    void rejectsInvalidWindowAndSupplierShape() {
        long campaign = campaign();
        rejects("ck_campaign_window", () -> jdbc.update(
                "UPDATE campaigns SET end_at=start_at WHERE id=?", campaign));
        rejects("ck_campaign_mode", () -> jdbc.update(
                "UPDATE campaigns SET supplier_id='unexpected' WHERE id=?", campaign));
    }

    @Test
    void permitsOneOrderPerCampaignUser() {
        long campaign = campaign();
        long original = order(campaign);
        rejects("uk_order_campaign_user", () -> order(campaign));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=?", Long.class, campaign)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM fulfillment_orders WHERE campaign_id=?", Long.class, campaign)).isEqualTo(original);
    }

    @Test
    void rejectsConfirmAndReleaseForSameOrder() {
        long campaign = campaign();
        long order = order(campaign);
        jdbc.update("INSERT INTO stock_ledger(order_id,campaign_id,entry_type,delta_available,delta_reserved,delta_issued) VALUES (?,?,'CONFIRM',0,-1,1)", order, campaign);
        rejects("uk_ledger_one_terminal", () -> jdbc.update(
                "INSERT INTO stock_ledger(order_id,campaign_id,entry_type,delta_available,delta_reserved,delta_issued) VALUES (?,?,'RELEASE',1,-1,0)", order, campaign));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE order_id=?", Long.class, order)).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateReserveAndInvalidDelta() {
        long campaign = campaign();
        long order = order(campaign);
        rejects("ck_ledger_delta", () -> jdbc.update(
                "INSERT INTO stock_ledger(order_id,campaign_id,entry_type,delta_available,delta_reserved,delta_issued) VALUES (?,?,'RESERVE',0,1,0)", order, campaign));
        jdbc.update("INSERT INTO stock_ledger(order_id,campaign_id,entry_type,delta_available,delta_reserved,delta_issued) VALUES (?,?,'RESERVE',-1,1,0)", order, campaign);
        rejects("uk_ledger_order_type", () -> jdbc.update(
                "INSERT INTO stock_ledger(order_id,campaign_id,entry_type,delta_available,delta_reserved,delta_issued) VALUES (?,?,'RESERVE',-1,1,0)", order, campaign));
    }

    @Test
    void permitsOnlyOneVoucherAndOneRedemption() {
        long campaign = campaign();
        long order = order(campaign);
        jdbc.update("UPDATE fulfillment_orders SET status='FULFILLED',completed_at=UTC_TIMESTAMP(3) WHERE id=?", order);
        long user = userId("user-demo");
        String insertVoucher = "INSERT INTO user_vouchers(order_id,campaign_id,user_id,status,expires_at) VALUES (?,?,?,'UNUSED',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))";
        jdbc.update(insertVoucher, order, campaign, user);
        long voucher = jdbc.queryForObject("SELECT id FROM user_vouchers WHERE order_id=?", Long.class, order);
        rejects("uk_voucher_order", () -> jdbc.update(insertVoucher, order, campaign, user));
        jdbc.update("UPDATE user_vouchers SET status='USED',used_at=UTC_TIMESTAMP(3) WHERE id=?", voucher);
        jdbc.update("INSERT INTO redemption_records(voucher_id,operator_id) VALUES (?,?)", voucher, userId("admin-demo"));
        rejects("uk_redemption_voucher", () -> jdbc.update(
                "INSERT INTO redemption_records(voucher_id,operator_id) VALUES (?,?)", voucher, userId("admin-demo")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM redemption_records WHERE voucher_id=?", Long.class, voucher)).isEqualTo(1);
    }

    @Test
    void rejectsTerminalOrderWithoutCompletionTime() {
        long order = order(campaign());
        rejects("ck_order_completion", () -> jdbc.update(
                "UPDATE fulfillment_orders SET status='FULFILLED' WHERE id=?", order));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment_orders WHERE id=?", String.class, order)).isEqualTo("PENDING");
    }

    @Test
    void rejectsUsedVoucherWithoutUsageTime() {
        long campaign = campaign();
        long order = order(campaign);
        rejects("ck_voucher_usage", () -> jdbc.update(
                "INSERT INTO user_vouchers(order_id,campaign_id,user_id,status,expires_at) VALUES (?,?,?,'USED',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",
                order, campaign, userId("user-demo")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_vouchers WHERE order_id=?", Long.class, order)).isZero();
    }

    private long campaign() {
        String name = "schema-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO campaigns(name,total_stock,available_stock,status,start_at,end_at,redeem_end_at,fulfillment_mode,created_by)
                VALUES (?,10,10,'DRAFT',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 HOUR),
                    DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),'LOCAL',?)
                """, name, userId("admin-demo"));
        return jdbc.queryForObject("SELECT id FROM campaigns WHERE name=?", Long.class, name);
    }

    private long order(long campaign) {
        String requestNo = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fulfillment_orders(request_no,campaign_id,user_id,fulfillment_mode,status,expires_at)
                VALUES (?,?,?,'LOCAL','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))
                """, requestNo, campaign, userId("user-demo"));
        return jdbc.queryForObject("SELECT id FROM fulfillment_orders WHERE request_no=?", Long.class, requestNo);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    }

    private void rejects(String constraint, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class, ex -> {
            assertThat(ex.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
                assertThat(sql.getErrorCode()).isEqualTo(constraint.startsWith("ck_") ? 3819 : 1062);
                assertThat(sql.getMessage()).contains(constraint);
            });
        });
    }
}
