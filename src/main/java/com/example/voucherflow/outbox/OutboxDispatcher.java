package com.example.voucherflow.outbox;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 根据空闲发送额度领取事件，并在线程池中执行发布和租约结算。 */
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxLeaseService leases;
    private final RocketMqEventSender sender;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private volatile boolean stopping;
    public OutboxDispatcher(OutboxLeaseService leases, RocketMqEventSender sender, ExecutorService executor, int capacity) {
        this.leases = leases; this.sender = sender; this.executor = executor; this.slots = new Semaphore(capacity);
    }
    /** 按空闲发送额度领取事件，已提交到线程池的任务自行归还额度。 */
    public void tick() {
        if (stopping || !ticking.compareAndSet(false, true)) { return; }
        // held 只记录尚未交给发送任务的额度；提交成功后，额度由 publish 的 finally 归还。
        int held = 0;
        try {
            while (slots.tryAcquire()) { held++; }
            if (held == 0) { return; }
            var events = leases.claim(held);
            slots.release(held - events.size());
            held = events.size();
            for (var event : events) {
                if (stopping) { break; }
                executor.execute(() -> publish(event));
                held--;
            }
        } catch (RuntimeException ex) {
            log.warn("Outbox dispatch failed: {}", ex.getClass().getSimpleName());
        } finally {
            // 提交被拒绝或停机时仅归还本地额度，已领取的数据库租约交给过期扫描恢复。
            slots.release(held);
            ticking.set(false);
        }
    }
    private void publish(LeasedEvent event) {
        try {
            boolean accepted = false;
            String failure = null;
            try {
                sender.send(event);
                accepted = true;
            } catch (Exception ex) { failure = ex.getClass().getSimpleName(); }
            // 发送成功与本地状态提交之间可能中断，重复发送由消费者按事件编号去重。
            boolean retained = leases.complete(event, accepted, failure);
            if (!retained) { log.info("Outbox lease lost eventId={}", event.eventId()); }
        } catch (RuntimeException ex) {
            log.warn("Outbox completion failed eventId={} category={}", event.eventId(), ex.getClass().getSimpleName());
        } finally { slots.release(); }
    }
    public void stop() { stopping = true; }
}
