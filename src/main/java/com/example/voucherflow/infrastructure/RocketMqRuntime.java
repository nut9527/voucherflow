package com.example.voucherflow.infrastructure;

import com.example.voucherflow.outbox.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 管理消息连接、发送与消费调度、过期租约回收以及客户端停机流程。 */
public class RocketMqRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RocketMqRuntime.class);
    private final MessagingProperties properties;
    private final OutboxLeaseService leases;
    private final ClientFactory factory;
    private final Consumer<MessageView> fulfillmentHandler;
    private final Consumer<MessageView> notificationHandler;
    private final com.example.voucherflow.common.ExecutionProbe probe;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5,
            Thread.ofPlatform().name("message-maintenance-", 0).factory());
    private final List<ExecutorService> pools;
    private final Object lifecycle = new Object();
    private volatile Session session;
    private volatile boolean stopping;

    public RocketMqRuntime(MessagingProperties properties, OutboxLeaseService leases, ClientFactory factory,
            Consumer<MessageView> fulfillmentHandler, Consumer<MessageView> notificationHandler,
            com.example.voucherflow.common.ExecutionProbe probe) {
        this.properties = properties; this.leases = leases; this.factory = factory;
        this.fulfillmentHandler = fulfillmentHandler; this.notificationHandler = notificationHandler;
        this.probe = probe;
        pools = List.of(pool(properties.sendThreads(), "outbox-send-"),
                pool(properties.consumerThreads(), "fulfillment-consume-"),
                pool(properties.consumerThreads(), "notification-consume-"));
    }
    // 发送、履约消费与通知消费分别使用有界线程池，隔离各自的处理容量。
    private static ExecutorService pool(int count, String name) {
        return new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(count),
                Thread.ofPlatform().name(name, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    /** 分别调度连接、两类消费、租约回收及事件发送，避免某一任务阻断整条链路。 */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::connect, 0, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            var current = session;
            if (current != null) { current.fulfillment().receiveTick(); }
        }, 0, 250, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            var current = session;
            if (current != null) { current.notifications().receiveTick(); }
        }, 0, 250, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            try { leases.recoverExpired(); }
            catch (RuntimeException ex) { log.warn("Outbox recovery failed: {}", ex.getClass().getSimpleName()); }
        }, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            var current = session;
            if (properties.publishingEnabled() && current != null && ready()) { current.dispatcher().tick(); }
        }, 1, 1, TimeUnit.SECONDS);
    }
    // 连接尚未建立时由定时任务继续尝试；发布会话与停机共用生命周期锁。
    private void connect() {
        if (stopping || session != null) { return; }
        try {
            var clients = factory.connect();
            var fulfillment = new RocketMqConsumerLoop("vf_fulfillment_worker", clients.fulfillment(), fulfillmentHandler,
                    pools.get(1), properties.consumerThreads(), properties.invisibleDuration(), probe);
            var notifications = new RocketMqConsumerLoop("vf_notification_worker", clients.notifications(), notificationHandler,
                    pools.get(2), properties.consumerThreads(), properties.invisibleDuration());
            var dispatcher = new OutboxDispatcher(leases, clients.sender(), pools.getFirst(), properties.sendThreads());
            synchronized (lifecycle) {
                if (!stopping) { session = new Session(clients, fulfillment, notifications, dispatcher); return; }
            }
            fulfillment.closeClient(); notifications.closeClient(); closeProducer(clients.producer());
        } catch (Exception ex) { log.warn("Messaging connection unavailable: {}", ex.getClass().getSimpleName()); }
    }
    /** 两个消费循环均完成初始化后，运行时才允许发布新的 Outbox 消息。 */
    public boolean ready() {
        var current = session;
        return !stopping && current != null && current.fulfillment().initialized() && current.notifications().initialized();
    }
    @Override public void close() {
        Session current;
        synchronized (lifecycle) {
            if (stopping) { return; }
            stopping = true; current = session;
        }
        if (current != null) {
            current.dispatcher().stop(); current.fulfillment().stop(); current.notifications().stop();
        }
        scheduler.shutdown(); pools.forEach(ExecutorService::shutdown);
        // 所有执行池共用一个停机期限，先等待在途任务，再关闭底层客户端。
        long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
        try {
            scheduler.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            for (var pool : pools) { pool.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        if (current != null) {
            current.fulfillment().closeClient(); current.notifications().closeClient(); closeProducer(current.clients().producer());
        }
        scheduler.shutdownNow(); pools.forEach(ExecutorService::shutdownNow);
    }
    private static void closeProducer(Producer producer) {
        try { producer.close(); } catch (Exception ex) { log.warn("Producer close failed: {}", ex.getClass().getSimpleName()); }
    }
    @FunctionalInterface public interface ClientFactory { Clients connect() throws Exception; }
    public record Clients(Producer producer, SimpleConsumer fulfillment, SimpleConsumer notifications, RocketMqEventSender sender) {}
    private record Session(Clients clients, RocketMqConsumerLoop fulfillment, RocketMqConsumerLoop notifications, OutboxDispatcher dispatcher) {}
}
