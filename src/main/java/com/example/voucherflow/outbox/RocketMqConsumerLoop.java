package com.example.voucherflow.outbox;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按可用线程额度拉取消息，在业务处理成功后确认消费。 */
public class RocketMqConsumerLoop {
    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumerLoop.class);
    private final String group;
    private final SimpleConsumer consumer;
    private final Consumer<MessageView> handler;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final Duration invisible;
    private final com.example.voucherflow.common.ExecutionProbe probe;
    private final Object lifecycle = new Object();
    private final AtomicBoolean receiving = new AtomicBoolean();
    private volatile boolean stopping;
    private boolean closed;
    private volatile long retryAfterNanos;
    private volatile boolean initialized;

    public RocketMqConsumerLoop(String group, SimpleConsumer consumer, Consumer<MessageView> handler,
            ExecutorService executor, int capacity, Duration invisible) {
        this(group, consumer, handler, executor, capacity, invisible, (point, identity) -> {});
    }
    public RocketMqConsumerLoop(String group, SimpleConsumer consumer, Consumer<MessageView> handler,
            ExecutorService executor, int capacity, Duration invisible, com.example.voucherflow.common.ExecutionProbe probe) {
        this.group = group; this.consumer = consumer; this.handler = handler; this.executor = executor;
        this.slots = new Semaphore(capacity); this.invisible = invisible;
        this.probe = probe;
    }
    /** 拉取数量不超过当前可用额度，失败退避期间暂停新一轮拉取。 */
    public void receiveTick() {
        if (stopping || System.nanoTime() < retryAfterNanos || !receiving.compareAndSet(false, true)) { return; }
        int held = 0;
        try {
            while (held < 10 && slots.tryAcquire()) { held++; }
            if (held == 0) { return; }
            // 拉取后消息即进入不可见期，先取得处理额度，减少消息在本地排队耗尽不可见期的风险。
            var messages = consumer.receive(held, invisible);
            initialized = true;
            slots.release(held - messages.size());
            held = messages.size();
            for (var message : messages) {
                if (stopping) { break; }
                executor.execute(() -> process(message));
                held--;
            }
        } catch (Exception ex) {
            retryAfterNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            log.warn("Message receive failed group={} category={}", group, ex.getClass().getSimpleName());
        } finally {
            slots.release(held);
            receiving.set(false);
        }
    }
    private void process(MessageView message) {
        try {
            // 业务处理及其事务先完成，再确认消息；失败时保留消息等待重新投递。
            handler.accept(message);
            // 履约组此时仅完成持久任务登记，实际发券由任务执行器继续推进。
            synchronized (lifecycle) {
                if (!closed) {
                    consumer.ack(message);
                    if (group.equals("vf_fulfillment_worker")) {
                        probe.hit("AFTER_FULFILLMENT_ACK", message.getProperties().get("eventId"));
                    }
                }
            }
        } catch (Exception ex) {
            // 业务已提交而确认失败时仍会重新投递，重复副作用由业务事务中的消费去重记录阻止。
            retryAfterNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            log.warn("Message processing failed group={} messageId={} delivery={} category={}",
                    group, message.getMessageId(), message.getDeliveryAttempt(), ex.getClass().getSimpleName());
        } finally { slots.release(); }
    }
    public boolean initialized() { return initialized; }
    public void stop() { stopping = true; }
    /** 关闭客户端与消息确认共用锁，避免关闭过程和确认操作交错。 */
    public void closeClient() {
        synchronized (lifecycle) {
            if (closed) { return; }
            closed = true;
            try { consumer.close(); }
            catch (Exception ex) { log.warn("Message client close failed group={}", group); }
        }
    }
}
