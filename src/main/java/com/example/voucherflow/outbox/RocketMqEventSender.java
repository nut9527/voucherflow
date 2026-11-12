package com.example.voucherflow.outbox;

import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 将已领取的 Outbox 事件按固定路由发送到 RocketMQ。 */
public class RocketMqEventSender {
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final EventCodec codec;
    private final com.example.voucherflow.common.ExecutionProbe probe;
    public RocketMqEventSender(ClientServiceProvider provider, Producer producer, EventCodec codec,
            com.example.voucherflow.common.ExecutionProbe probe) {
        this.provider = provider; this.producer = producer; this.codec = codec;
        this.probe = probe;
    }
    /** 发送原始信封并沿用事件编号作为消息键，发布操作不参与数据库事务。 */
    public void send(LeasedEvent leased) throws ClientException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cannot publish within a database transaction");
        }
        var event = codec.decode(leased.payload());
        if (!event.eventId().equals(leased.eventId()) || !event.eventType().equals(leased.eventType())) {
            throw new IllegalArgumentException("Outbox envelope identity mismatch");
        }
        var route = EventCodec.route(event.eventType());
        var message = provider.newMessageBuilder().setTopic(route.topic()).setTag(route.tag())
                .setKeys(event.eventId()).addProperty("eventId", event.eventId())
                .setBody(leased.payload().getBytes(StandardCharsets.UTF_8)).build();
        try (var context = EventLogContext.open(event)) {
            var receipt = producer.send(message);
            if (receipt == null) { throw new IllegalStateException("No broker send receipt"); }
            probe.hit("AFTER_OUTBOX_SEND", event.eventId());
        }
    }
}
