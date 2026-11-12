package com.example.voucherflow.fulfillment;

import com.example.voucherflow.outbox.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 核对通知事件引用的订单和券，提供生成站内通知所需的业务事实。 */
@Service
public class NotificationFactsQuery {
    private final JdbcTemplate jdbc;
    public NotificationFactsQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 消费事务内核实事件引用，通知内容以已提交的业务事实为依据。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationFacts findForEvent(DomainEvent event) {
        long orderId = Long.parseLong(event.data().get("orderId"));
        long userId = Long.parseLong(event.data().get("userId"));
        var facts = jdbc.query("""
                SELECT v.id AS voucher_id,o.status,
                (v.user_id=o.user_id AND v.campaign_id=o.campaign_id) AS voucher_matches FROM fulfillment_orders o
                LEFT JOIN user_vouchers v ON v.order_id=o.id
                WHERE o.id=? AND o.user_id=?
                """, (rs, n) -> new Fact(rs.getObject(1, Long.class), rs.getString(2), rs.getBoolean(3)), orderId, userId);
        if (facts.size() != 1) { throw new IllegalArgumentException("Missing notification fact"); }
        var fact = facts.getFirst();
        boolean valid = switch (event.eventType()) {
            case "VoucherIssued.v1" -> fact.status().equals("FULFILLED") && fact.voucherId() != null && fact.voucherMatches()
                    && fact.voucherId().toString().equals(event.data().get("voucherId"));
            case "FulfillmentFailed.v1" -> fact.status().equals("FAILED") && fact.voucherId() == null;
            default -> false;
        };
        if (!valid) { throw new IllegalArgumentException("Conflicting notification fact"); }
        return new NotificationFacts(userId, orderId, fact.voucherId());
    }
    private record Fact(Long voucherId, String status, boolean voucherMatches) {}
}
