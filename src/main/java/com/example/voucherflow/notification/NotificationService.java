package com.example.voucherflow.notification;

import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.common.PageResult;
import com.example.voucherflow.fulfillment.NotificationFactsQuery;
import com.example.voucherflow.identity.CurrentUser;
import com.example.voucherflow.outbox.ConsumedEventStore;
import com.example.voucherflow.outbox.DomainEvent;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 消费履约终态事件生成站内通知，并按接收用户隔离查询结果。 */
@Service
public class NotificationService {
    public static final String CONSUMER = "vf_notification_worker";
    private final JdbcTemplate jdbc;
    private final ConsumedEventStore receipts;
    private final NotificationFactsQuery facts;
    private final CurrentUser currentUser;
    public NotificationService(JdbcTemplate jdbc, ConsumedEventStore receipts, NotificationFactsQuery facts, CurrentUser currentUser) {
        this.jdbc = jdbc; this.receipts = receipts; this.facts = facts; this.currentUser = currentUser;
    }
    /** 将消费回执与通知写入同一事务，重复投递不会重复生成通知。 */
    public void consume(DomainEvent event) {
        receipts.consume(CONSUMER, event, () -> {
            // 通知内容依据本地已落库的履约事实构建。
            var fact = facts.findForEvent(event);
            jdbc.update("""
                    INSERT INTO notifications(event_id,user_id,order_id,voucher_id,channel,content,created_at)
                    VALUES(?,?,?,?,'IN_APP',?,UTC_TIMESTAMP(3))
                    """, event.eventId(), fact.userId(), fact.orderId(), fact.voucherId(),
                    fact.voucherId() == null ? "权益发放未完成，预留名额已释放。" : "权益已发放，可在券包中查看。" );
        });
    }
    /** 按登录用户过滤接收人，并多查询一条记录判断是否还有下一页。 */
    public PageResult<Summary> ownedPage(PageQuery query) {
        long userId = currentUser.requireUserId();
        var rows = jdbc.query("""
                SELECT id,order_id,voucher_id,channel,content,created_at FROM notifications
                WHERE user_id=? AND id>? ORDER BY id LIMIT ?
                """, (rs, n) -> new Summary(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getObject(6, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)),
                userId, query.afterId(), query.size() + 1);
        return PageResult.of(rows, query.size(), Summary::id);
    }
    public record Summary(String id, String orderId, String voucherId, String channel, String content, Instant createdAt) {}
}
