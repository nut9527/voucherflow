package com.example.voucherflow.outbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 将消费去重记录与业务处理放在同一事务中，支持消息重复投递。 */
@Service
public class ConsumedEventStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventCodec codec;

    public ConsumedEventStore(JdbcTemplate jdbc, TransactionTemplate tx, EventCodec codec) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.codec = codec;
    }

    /** 独立提交去重记录与业务副作用，返回后消息循环才能确认消费。 */
    @Transactional(propagation = Propagation.NEVER)
    public void consume(String consumer, DomainEvent event, Runnable effect) {
        String hash = codec.payloadHash(event);
        tx.executeWithoutResult(
                status -> {
                    try {
                        jdbc.update(
                                """
                        INSERT INTO consumed_events(consumer_name,event_id,payload_hash,consumed_at)
                        VALUES(?,?,?,UTC_TIMESTAMP(3))
                        """,
                                consumer,
                                event.eventId(),
                                hash);
                    } catch (DuplicateKeyException ex) {
                        // 重复事件只跳过相同内容；同一事件编号携带不同事实视为身份冲突。
                        String existing =
                                jdbc.queryForObject(
                                        "SELECT payload_hash FROM consumed_events WHERE consumer_name=? AND event_id=?",
                                        String.class,
                                        consumer,
                                        event.eventId());
                        if (!hash.equals(existing)) {
                            throw new IllegalStateException(
                                    "Conflicting consumed event identity", ex);
                        }
                        return;
                    }
                    // 业务失败时连同去重记录一起回滚，下一次投递仍可重新执行。
                    effect.run();
                });
    }
}
