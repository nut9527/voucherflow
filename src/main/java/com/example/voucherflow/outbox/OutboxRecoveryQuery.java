package com.example.voucherflow.outbox;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** 为对账与修复查询失败事件和剩余人工发布额度。 */
@Service
public class OutboxRecoveryQuery {
    private static final String SELECT = """
            SELECT id,order_id,
            event_type,state,manual_publish_credits,manual_publish_granted_total FROM outbox_events
            """;
    private static final RowMapper<EventRecoveryView> ROW = (rs, n) -> new EventRecoveryView(rs.getString(1),
            rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6));
    private final JdbcTemplate jdbc;
    public OutboxRecoveryQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<EventRecoveryView> findFailedForOrders(List<Long> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        return jdbc.query(SELECT + " WHERE order_id IN (" + placeholders
                + ") AND state='FAILED' ORDER BY order_id,id", ROW, orderIds.toArray());
    }
    public Optional<EventRecoveryView> findByEventId(String id) {
        return jdbc.query(SELECT + " WHERE id=?", ROW, id).stream().findFirst();
    }
}
