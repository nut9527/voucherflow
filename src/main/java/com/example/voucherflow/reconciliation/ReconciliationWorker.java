package com.example.voucherflow.reconciliation;

import com.example.voucherflow.fulfillment.SupplierEvidenceService;
import com.example.voucherflow.outbox.EventRecoveryView;
import com.example.voucherflow.outbox.OutboxRecoveryQuery;
import com.example.voucherflow.reconciliation.DifferenceClassifier.Classification;
import com.example.voucherflow.reconciliation.DifferenceClassifier.Observation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 分批对比本地订单与供应商结果，将差异和扫描进度一并持久化。 */
@Component
public class ReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);
    // 单次供应商请求最多等待五秒，每十单续租一次，留出距 120 秒租约到期的余量。
    private static final int RENEW_EVERY = 10;

    private record Finding(
            Observation order,
            Classification classification,
            long version,
            String eventId,
            String evidence) {}

    private final ReconciliationStore store;
    private final SupplierEvidenceService supplier;
    private final OutboxRecoveryQuery outbox;
    private final LocalReconciliationService local;
    private final DifferenceClassifier classifier;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ReconciliationProperties properties;
    private final Clock clock;

    public ReconciliationWorker(
            ReconciliationStore store,
            SupplierEvidenceService supplier,
            OutboxRecoveryQuery outbox,
            LocalReconciliationService local,
            DifferenceClassifier classifier,
            TransactionTemplate tx,
            ObjectMapper json,
            ReconciliationProperties properties,
            Clock clock) {
        this.store = store;
        this.supplier = supplier;
        this.outbox = outbox;
        this.local = local;
        this.classifier = classifier;
        this.tx = tx;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${voucherflow.reconciliation.interval-ms:3000}")
    public void tick() {
        try {
            store.claim().ifPresent(this::process);
        } catch (RuntimeException ex) {
            log.warn("Reconciliation batch failed", ex);
        }
    }

    /** 事务外查询远程证据，最后在短事务内提交本批差异与进度。 */
    public void process(ReconciliationStore.Lease lease) {
        if (!store.renew(lease)) return;
        // 三十秒是按订单检查的批次预算，达到预算后提交已处理部分，不中断正在进行的查单。
        var deadline = clock.instant().plusSeconds(30);
        var accounting = lease.accounting();
        // 同一对账任务复用首次记录的账目检查结果，保证各批差异使用相同检查依据。
        if (accounting == null) {
            var stats = local.inspect(lease.campaignId());
            accounting = new ReconciliationStore.Accounting(stats.consistent(), stats.observedAt());
            if (!store.saveAccounting(lease, accounting)) return;
        }
        var page = store.page(lease);
        var failedEvents =
                outbox.findFailedForOrders(page.stream().map(Observation::id).toList()).stream()
                        .collect(Collectors.groupingBy(EventRecoveryView::orderId));
        var findings = new ArrayList<Finding>();
        int processed = 0;
        for (var before : page) {
            if (processed % RENEW_EVERY == 0 && !store.renew(lease)) return;
            String remote = "LOCAL";
            Long version = null;
            if (before.mode().equals("SUPPLIER")) {
                var evidence = supplier.observe(before.id());
                remote = evidence.status();
                version = evidence.resultVersion();
            }
            // before 来自本页读取，after 在远程查询后重读；两次版本由分类器比较，识别期间的本地推进。
            var after = store.order(before.id());
            var result = classifier.classify(before, remote, after, accounting.valid());
            if (result != null) {
                findings.add(
                        new Finding(
                                after,
                                result,
                                version == null ? 0 : version,
                                null,
                                json.writeValueAsString(
                                        Map.of(
                                                "supplierStatus", remote,
                                                "accountingValid", accounting.valid(),
                                                "accountingObservedAt",
                                                        accounting.observedAt().toString(),
                                                "observedAt", clock.instant().toString()))));
            }
            for (var event : failedEvents.getOrDefault(before.id(), List.of())) {
                findings.add(
                        new Finding(
                                after,
                                new Classification(
                                        "OUTBOX_PUBLISH_FAILED",
                                        "OPEN",
                                        List.of(RepairOperation.REPLAY_OUTBOX)),
                                0,
                                event.eventId(),
                                "{\"publishState\":\"FAILED\"}"));
            }
            processed++;
            if (!clock.instant().isBefore(deadline)) break;
        }
        int count = processed;
        // 预算可能只够处理半页，游标只越过已处理的前缀，未处理订单留给下一份租约。
        long cursor = count == 0 ? lease.cursor() : page.get(count - 1).id();
        // 短页仅在整页处理完后才代表扫描结束，不能把因时间预算提前退出误当作完成。
        boolean completed =
                cursor >= lease.upperOrderId()
                        || (count == page.size() && page.size() < properties.getBatchSize());
        // 差异与游标原子提交，执行中断后可从最后成功的批次继续。
        tx.executeWithoutResult(
                status -> {
                    // 查询期间产生的发现仍只在内存中；租约已失效就整批丢弃，由接管者从旧游标重扫。
                    if (!store.owns(lease)) return;
                    for (var finding : findings) {
                        store.insertDiff(
                                lease,
                                finding.order(),
                                finding.classification(),
                                finding.version(),
                                finding.eventId(),
                                finding.evidence());
                    }
                    store.advance(lease, cursor, count, completed);
                });
    }
}
