package com.example.voucherflow;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

/** 从旧版表结构与业务数据升级，验证履约事实保留及新增字段的迁移结果。 */
class MigrationUpgradeIT {
    @Test void upgradePreservesExistingLocalFactsAndMarksThemDone() throws Exception {
        try(var mysql=new MySQLContainer("mysql:8.4").withDatabaseName("upgrade_test")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
                    .locations("classpath:db/migration").target("2").load().migrate();
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
                var sql=connection.createStatement()) {
                sql.executeUpdate("INSERT INTO users(id,username,password_hash,role) VALUES(1,'upgrade-admin','unused','ADMIN'),(2,'upgrade-user','unused','USER')");
                sql.executeUpdate("""
                    INSERT INTO campaigns(id,name,total_stock,available_stock,reserved_stock,issued_stock,status,
                      start_at,end_at,redeem_end_at,fulfillment_mode,created_by)
                    VALUES(1,'upgrade',2,1,0,1,'ACTIVE',UTC_TIMESTAMP(3),
                      TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(3)),TIMESTAMPADD(HOUR,2,UTC_TIMESTAMP(3)),'LOCAL',1)
                    """);
                sql.executeUpdate("""
                    INSERT INTO fulfillment_orders(id,request_no,campaign_id,user_id,fulfillment_mode,status,expires_at,completed_at)
                    VALUES(1,UUID(),1,2,'LOCAL','FULFILLED',TIMESTAMPADD(HOUR,2,UTC_TIMESTAMP(3)),UTC_TIMESTAMP(3))
                    """);
                sql.executeUpdate("""
                    INSERT INTO stock_ledger(order_id,campaign_id,entry_type,delta_available,delta_reserved,delta_issued)
                    VALUES(1,1,'RESERVE',-1,1,0),(1,1,'CONFIRM',0,-1,1)
                    """);
                sql.executeUpdate("""
                    INSERT INTO user_vouchers(id,order_id,campaign_id,user_id,status,expires_at)
                    VALUES(1,1,1,2,'UNUSED',TIMESTAMPADD(HOUR,2,UTC_TIMESTAMP(3)))
                    """);
                sql.executeUpdate("""
                    INSERT INTO outbox_events(id,event_type,aggregate_type,aggregate_id,payload)
                    VALUES(UUID(),'VoucherIssued.v1','USER_VOUCHER','1',JSON_OBJECT('data',JSON_OBJECT('orderId','1')))
                    """);
                Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
                        .locations("classpath:db/migration").target("5").load().migrate();
                sql.executeUpdate("INSERT INTO reconciliation_runs(campaign_id,cutoff_at,state) VALUES(1,UTC_TIMESTAMP(3),'READY')");
                Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
                        .locations("classpath:db/migration").load().migrate();
                try(var rows=sql.executeQuery("""
                    SELECT o.status,o.job_state,c.available_stock,c.reserved_stock,c.issued_stock,
                      (SELECT COUNT(*) FROM stock_ledger) AS ledger_count,
                      (SELECT COUNT(*) FROM user_vouchers) AS voucher_count,
                      (SELECT upper_order_id FROM reconciliation_runs WHERE campaign_id=1) AS scan_boundary
                    FROM fulfillment_orders o JOIN campaigns c ON c.id=o.campaign_id WHERE o.id=1
                    """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("FULFILLED");assertThat(rows.getString(2)).isEqualTo("DONE");
                    assertThat(rows.getInt(3)).isEqualTo(1);assertThat(rows.getInt(4)).isZero();assertThat(rows.getInt(5)).isEqualTo(1);
                    assertThat(rows.getInt(6)).isEqualTo(2);assertThat(rows.getInt(7)).isEqualTo(1);
                    assertThat(rows.getLong(8)).isEqualTo(1);
                }
                try (var rows = sql.executeQuery("SELECT order_id FROM outbox_events")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                }
                try (var rows = sql.executeQuery("SELECT accounting_valid,accounting_observed_at FROM reconciliation_runs")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isNull();
                    assertThat(rows.getObject(2)).isNull();
                }
            }
        }
    }
}
