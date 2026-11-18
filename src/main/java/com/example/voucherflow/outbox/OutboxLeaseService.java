package com.example.voucherflow.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 通过数据库租约协调多实例发布，管理发送结果、退避重试和过期恢复。 */
@Service
public class OutboxLeaseService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MessagingProperties properties;

    public OutboxLeaseService(
            JdbcTemplate jdbc, TransactionTemplate tx, MessagingProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
    }

    /** 用短事务和跳锁查询分配事件，网络发送在领取事务提交后进行。 */
    public List<LeasedEvent> claim(int slots) {
        if (slots <= 0) {
            return List.of();
        }
        return tx.execute(
                status -> {
                    var ids =
                            jdbc.queryForList(
                                    """
                    SELECT id FROM outbox_events WHERE state='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)
                    AND (attempt_count<? OR manual_publish_credits>0)
                    ORDER BY next_attempt_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                    """,
                                    String.class,
                                    properties.maxAttempts(),
                                    Math.min(slots, 50));
                    var result = new ArrayList<LeasedEvent>();
                    for (String id : ids) {
                        String token = UUID.randomUUID().toString();
                        // 领取时就累计尝试次数，进程中断也不会重置预算；每次领取使用新令牌区分执行者。
                        // 人工额度仅在自动预算耗尽后扣除，判断使用本次 attempt_count 递增前的值。
                        jdbc.update(
                                """
                        UPDATE outbox_events SET state='SENDING',lease_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(3)),
                        manual_publish_credits=manual_publish_credits-IF(attempt_count>=?,1,0),attempt_count=attempt_count+1
                        WHERE id=? AND state='PENDING' AND (attempt_count<? OR manual_publish_credits>0)
                        """,
                                token,
                                properties.lease().toNanos() / 1000,
                                properties.maxAttempts(),
                                id,
                                properties.maxAttempts());
                        result.add(
                                jdbc.queryForObject(
                                        "SELECT id,event_type,payload,lease_token,attempt_count FROM outbox_events WHERE id=?",
                                        (rs, n) ->
                                                new LeasedEvent(
                                                        rs.getString(1),
                                                        rs.getString(2),
                                                        rs.getString(3),
                                                        rs.getString(4),
                                                        rs.getInt(5)),
                                        id));
                    }
                    return result;
                });
    }

    /** 只有仍持有有效令牌的发送者可以结算，过期发送者不能覆盖后续重试。 */
    public boolean complete(LeasedEvent event, boolean accepted, String failureKind) {
        return tx.execute(
                status -> {
                    if (accepted) {
                        // SENT 只表示消息代理已接收，业务消费者是否处理完成由各自的消费回执记录。
                        return jdbc.update(
                                        """
                        UPDATE outbox_events SET state='SENT',sent_at=UTC_TIMESTAMP(3),lease_token=NULL,lease_until=NULL,last_error=NULL
                        WHERE id=? AND state='SENDING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(3)
                        """,
                                        event.eventId(),
                                        event.leaseToken())
                                == 1;
                    }
                    return jdbc.update(
                                    """
                    UPDATE outbox_events SET state=IF(attempt_count>=? AND manual_publish_credits=0,'FAILED','PENDING'),
                    next_attempt_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(3)),last_error=?,lease_token=NULL,lease_until=NULL
                    WHERE id=? AND state='SENDING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(3)
                    """,
                                    properties.maxAttempts(),
                                    backoffMillis(event.attemptCount()) * 1000,
                                    failureKind,
                                    event.eventId(),
                                    event.leaseToken())
                            == 1;
                });
    }

    /** 发送结果未能落库时回收过期租约，后续可能重复投递同一事件。 */
    public int recoverExpired() {
        return tx.execute(
                status -> {
                    var rows =
                            jdbc.query(
                                    """
                    SELECT id,lease_token,lease_until FROM outbox_events WHERE state='SENDING'
                    AND lease_until<=UTC_TIMESTAMP(3) ORDER BY lease_until,id LIMIT 50 FOR UPDATE SKIP LOCKED
                    """,
                                    (rs, n) ->
                                            new Expired(
                                                    rs.getString(1),
                                                    rs.getString(2),
                                                    rs.getObject(3, java.time.LocalDateTime.class)
                                                            .toInstant(java.time.ZoneOffset.UTC)));
                    int count = 0;
                    for (var row : rows) {
                        count +=
                                jdbc.update(
                                        """
                        UPDATE outbox_events SET state=IF(attempt_count>=? AND manual_publish_credits=0,'FAILED','PENDING'),
                        next_attempt_at=UTC_TIMESTAMP(3),lease_token=NULL,lease_until=NULL,last_error='LEASE_EXPIRED'
                        WHERE id=? AND state='SENDING' AND lease_token=? AND lease_until=? AND lease_until<=UTC_TIMESTAMP(3)
                        """,
                                        properties.maxAttempts(),
                                        row.id(),
                                        row.token(),
                                        java.time.LocalDateTime.ofInstant(
                                                row.until(), java.time.ZoneOffset.UTC));
                    }
                    return count;
                });
    }

    /** 指数退避叠加少量随机延迟，分散多实例同时重试的时间。 */
    static long backoffMillis(int attempt) {
        return Math.min(60000L, 1000L << Math.min(6, Math.max(0, attempt - 1)))
                + ThreadLocalRandom.current().nextLong(251);
    }

    private record Expired(String id, String token, Instant until) {}
}
