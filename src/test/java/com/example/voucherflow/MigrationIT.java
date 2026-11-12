package com.example.voucherflow;

import com.example.voucherflow.identity.mapper.UserMapper;
import com.example.voucherflow.infrastructure.BusinessMetrics;
import com.example.voucherflow.infrastructure.UtcInstantTypeHandler;
import com.example.voucherflow.support.MySqlIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.type.JdbcType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证数据库迁移历史、索引、会话配置和 UTC 时间映射。 */
class MigrationIT extends MySqlIntegrationTest {
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private UserMapper users;

    @Test
    void migrationHistoryAndSessionIndexesArePresent() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1", String.class))
                .contains("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'SPRING_SESSION'
                  AND index_name IN ('SPRING_SESSION_IX1', 'SPRING_SESSION_IX2', 'SPRING_SESSION_IX3')
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void businessMetricsHaveStateAndAgeIndexes() {
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(table_name, ':', index_name, ':',
                    GROUP_CONCAT(column_name ORDER BY seq_in_index))
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND index_name IN ('ix_outbox_state_created', 'ix_order_status_created', 'ix_diff_state')
                GROUP BY table_name, index_name
                """, String.class)).containsExactlyInAnyOrder(
                "outbox_events:ix_outbox_state_created:state,created_at",
                "fulfillment_orders:ix_order_status_created:status,created_at",
                "reconciliation_diffs:ix_diff_state:state");
    }

    @Test
    @Transactional
    void indexedMetricsPreservePendingCountAndOldestAge() {
        for (String state : new String[] {"PENDING", "SENDING", "SENT"}) {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO outbox_events(id,event_type,aggregate_type,aggregate_id,payload,state,created_at)
                    VALUES (?,'MetricsFixture','TEST',?,'{}',?,TIMESTAMPADD(SECOND,-90,UTC_TIMESTAMP(3)))
                    """, id, id, state);
        }
        var registry = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(jdbc, registry);
        try {
            metrics.refresh();
            assertThat(registry.get("vf.metrics.last.refresh.epoch.seconds").gauge().value()).isPositive();
            assertThat(registry.get("vf.outbox.pending").gauge().value()).isEqualTo(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE state IN ('PENDING','SENDING')", Double.class));
            double expectedAge = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3))),0)
                    FROM outbox_events WHERE state IN ('PENDING','SENDING')
                    """, Double.class);
            assertThat(registry.get("vf.outbox.oldest.pending.seconds").gauge().value())
                    .isBetween(expectedAge - 2, expectedAge);
        } finally {
            registry.close();
        }
    }

    @Test
    void reapplyingMigrationsPreservesExistingUsersAndHistory() {
        var userId = users.findByUsername("admin-demo").orElseThrow().getId();
        var before = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
        flyway.migrate();
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(users.findByUsername("admin-demo").orElseThrow().getId()).isEqualTo(userId);
    }

    @Test
    void connectionsUseReadCommittedAndUtc() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT @@session.transaction_isolation, @@session.time_zone")) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("READ-COMMITTED");
            assertThat(result.getString(2)).isEqualTo("+00:00");
        }
    }

    @Test
    void instantHandlerRoundTripsUtcMillisecondsThroughMysql() throws Exception {
        String username = "utc-" + UUID.randomUUID();
        Instant expected = Instant.ofEpochMilli(1_700_000_000_123L);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO users(username, password_hash, role, created_at) VALUES (?, 'unused', 'USER', ?)
                """)) {
            statement.setString(1, username);
            new UtcInstantTypeHandler().setNonNullParameter(statement, 2, expected, JdbcType.TIMESTAMP);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        assertThat(users.findByUsername(username).orElseThrow().getCreatedAt()).isEqualTo(expected);
        assertThat(users.findByUsername("missing-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void usernamesAreLowercaseAndUnique() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users(username, password_hash, role) VALUES ('UPPER-CASE', 'unused', 'USER')
                """)).isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(error.getCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains("ck_users_username");
                }));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users(username, password_hash, role) VALUES ('user-demo', 'unused', 'USER')
                """)).isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(error.getCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(1062);
                    assertThat(sql.getMessage()).contains("uk_users_username");
                }));
        assertThat(users.findByUsername("UPPER-CASE")).isEmpty();
        assertThat(users.findByUsername("user-demo")).isPresent();
    }
}
