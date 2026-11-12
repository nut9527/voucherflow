package com.example.voucherflow.fulfillment;

import com.example.voucherflow.common.ExecutionProbe;
import com.example.voucherflow.fulfillment.supplier.HttpSupplierClient;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按本地并发额度执行持久履约任务，并定期恢复租约和处理待应用回执。 */
@Component
public class FulfillmentWorker {
    private static final Logger log = LoggerFactory.getLogger(FulfillmentWorker.class);
    private final FulfillmentProperties properties;
    private final FulfillmentJobService jobs;
    private final SupplierEvidenceService evidence;
    private final HttpSupplierClient client;
    private final FulfillmentFinalizer finalizer;
    private final SupplierStore store;
    private final ExecutionProbe probe;
    private final ExecutorService executor;
    private final Semaphore slots;
    private volatile boolean stopping;

    public FulfillmentWorker(FulfillmentProperties properties, FulfillmentJobService jobs,
            SupplierEvidenceService evidence, HttpSupplierClient client, FulfillmentFinalizer finalizer,
            SupplierStore store, ExecutionProbe probe) {
        this.properties = properties;
        this.jobs = jobs;
        this.evidence = evidence;
        this.client = client;
        this.finalizer = finalizer;
        this.store = store;
        this.probe = probe;
        slots = new Semaphore(properties.getConcurrency());
        executor = new ThreadPoolExecutor(properties.getConcurrency(), properties.getConcurrency(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getConcurrency()),
                Thread.ofPlatform().name("supplier-worker-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** 先预留本地执行额度，再领取数据库任务，减少任务持有租约后的排队时间。 */
    @Scheduled(fixedDelayString = "${voucherflow.fulfillment.poll-ms:500}")
    public void tick() {
        if (stopping || !properties.isEnabled() || !properties.isWorkerEnabled()) return;
        int reserved = 0;
        while (slots.tryAcquire()) reserved++;
        if (reserved == 0) return;
        int handed = 0;
        try {
            for (var execution : jobs.claim(reserved)) {
                executor.execute(() -> {
                    try {
                        execute(execution);
                    } catch (RuntimeException ex) {
                        log.warn("Supplier execution deferred: orderId={}, error={}",
                                execution.order().id(), ex.getClass().getSimpleName());
                    } finally {
                        slots.release();
                    }
                });
                handed++;
            }
        } finally {
            slots.release(reserved - handed);
        }
    }

    private void execute(FulfillmentJobService.Execution execution) {
        String outcome = "TECHNICAL_FAILURE";
        try {
            evidence.requireRoute(execution.order());
            if ("ISSUE".equals(execution.action()) && !store.now().isBefore(execution.order().deadline())) {
                outcome = "WINDOW_EXPIRED";
                return;
            }
            var result = client.call(execution.order().request(), execution.action());
            probe.hit("AFTER_SUPPLIER_RESULT", Long.toString(execution.order().id()));
            outcome = result.status();
            // 先独立存证再应用终态；中途退出后，维护任务可继续处理已落库回执。
            long receipt = evidence.record(execution.order().id(), "attempt:" + execution.attemptId(),
                    "WORKER", execution.token(), result);
            finalizer.applyReceipt(receipt);
        } finally {
            jobs.finish(execution, outcome);
        }
    }

    /** 回收过期执行，并重试已经存证但尚未完成本地应用的回执。 */
    @Scheduled(fixedDelayString = "${voucherflow.fulfillment.maintenance-ms:1000}")
    public void maintain() {
        if (stopping || !properties.isEnabled()) return;
        jobs.recoverExpired();
        for (long id : store.pendingReceipts(32)) {
            try {
                finalizer.applyReceipt(id);
            } catch (RuntimeException ex) {
                log.warn("Supplier receipt deferred: receiptId={}, error={}", id, ex.getClass().getSimpleName());
                try {
                    store.deferReceipt(id);
                } catch (RuntimeException schedulingFailure) {
                    log.warn("Supplier receipt retry scheduling failed: receiptId={}, error={}",
                            id, schedulingFailure.getClass().getSimpleName());
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(7, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
