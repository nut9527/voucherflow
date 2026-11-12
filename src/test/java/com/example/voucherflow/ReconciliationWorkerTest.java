package com.example.voucherflow;

import com.example.voucherflow.fulfillment.ReceiptEvidence;
import com.example.voucherflow.fulfillment.SupplierEvidenceService;
import com.example.voucherflow.outbox.OutboxRecoveryQuery;
import com.example.voucherflow.reconciliation.DifferenceClassifier;
import com.example.voucherflow.reconciliation.DifferenceClassifier.Observation;
import com.example.voucherflow.reconciliation.LocalReconciliationService;
import com.example.voucherflow.reconciliation.LocalStats;
import com.example.voucherflow.reconciliation.ReconciliationProperties;
import com.example.voucherflow.reconciliation.ReconciliationStore;
import com.example.voucherflow.reconciliation.ReconciliationStore.Accounting;
import com.example.voucherflow.reconciliation.ReconciliationStore.Lease;
import com.example.voucherflow.reconciliation.ReconciliationWorker;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证扫描沿用持久化快照与固定上界，并在租约或时间预算耗尽时保留正确进度。 */
class ReconciliationWorkerTest {
    private final ReconciliationStore store = mock(ReconciliationStore.class);
    private final SupplierEvidenceService supplier = mock(SupplierEvidenceService.class);
    private final OutboxRecoveryQuery outbox = mock(OutboxRecoveryQuery.class);
    private final LocalReconciliationService local = mock(LocalReconciliationService.class);
    private final ReconciliationProperties properties = new ReconciliationProperties();
    private final Instant observedAt = Instant.parse("2026-01-01T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(observedAt);
    private final Clock clock = mock(Clock.class);

    private ReconciliationWorker worker() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(clock.instant()).thenAnswer(call -> now.get());
        return new ReconciliationWorker(store, supplier, outbox, local, new DifferenceClassifier(),
                new TransactionTemplate(manager), new ObjectMapper(), properties, clock);
    }

    @Test
    void laterPageUsesPersistedAccountingSnapshotAndCompletesAtFrozenUpperBound() {
        properties.setBatchSize(1);
        var first = new Lease(1, 2, 0, 12, "first", null);
        var accounting = new Accounting(true, observedAt);
        var second = new Lease(1, 2, 11, 12, "second", accounting);
        var stats = mock(LocalStats.class);
        when(stats.consistent()).thenReturn(true);
        when(stats.observedAt()).thenReturn(observedAt);
        when(local.inspect(2)).thenReturn(stats);
        when(store.saveAccounting(first, accounting)).thenReturn(true);
        when(store.renew(any())).thenReturn(true);
        when(store.owns(any())).thenReturn(true);
        when(store.page(first)).thenReturn(List.of(order(11, "LOCAL")));
        when(store.page(second)).thenReturn(List.of(order(12, "LOCAL")));
        when(store.order(anyLong())).thenAnswer(call -> order(call.getArgument(0), "LOCAL"));

        var worker = worker();
        worker.process(first);
        worker.process(second);

        verify(local, times(1)).inspect(2);
        verify(store, times(1)).saveAccounting(first, accounting);
        verify(outbox).findFailedForOrders(List.of(11L));
        verify(outbox).findFailedForOrders(List.of(12L));
        verify(store).advance(first, 11, 1, false);
        verify(store).advance(second, 12, 1, true);
    }

    @Test
    void expiredLeaseDoesNotScanOrQuerySupplier() {
        var lease = new Lease(1, 2, 0, 11, "expired", null);
        worker().process(lease);
        verifyNoInteractions(local, supplier, outbox);
        verify(store, never()).page(any());
        verify(store, never()).advance(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void losingLeaseDuringBatchStopsRemoteQueriesAndDiscardsUncommittedFindings() {
        var lease = new Lease(1, 2, 0, 11, "lease", new Accounting(true, observedAt));
        when(store.renew(lease)).thenReturn(true, true, false);
        when(store.page(lease)).thenReturn(LongStream.rangeClosed(1, 11)
                .mapToObj(id -> order(id, "SUPPLIER")).toList());
        when(supplier.observe(anyLong())).thenReturn(new ReceiptEvidence(0, "UNAVAILABLE", null, null));
        when(store.order(anyLong())).thenAnswer(call -> order(call.getArgument(0), "SUPPLIER"));

        worker().process(lease);

        verify(supplier, times(10)).observe(anyLong());
        verify(supplier, never()).observe(11);
        verify(store, never()).insertDiff(any(), any(), any(), anyLong(), any(), anyString());
        verify(store, never()).advance(any(), anyLong(), anyInt(), anyBoolean());
        verifyNoInteractions(local);
    }

    @Test
    void timeBudgetCommitsOnlyProcessedPrefixAndNextLeaseFinishesRemainingOrders() {
        var accounting = new Accounting(true, observedAt);
        var first = new Lease(1, 2, 0, 3, "first", accounting);
        var second = new Lease(1, 2, 2, 3, "second", accounting);
        when(store.renew(any())).thenReturn(true);
        when(store.owns(any())).thenReturn(true);
        when(store.page(first)).thenReturn(LongStream.rangeClosed(1, 3)
                .mapToObj(id -> order(id, "SUPPLIER")).toList());
        when(store.page(second)).thenReturn(List.of(order(3, "SUPPLIER")));
        when(store.order(anyLong())).thenAnswer(call -> order(call.getArgument(0), "SUPPLIER"));
        when(supplier.observe(anyLong())).thenAnswer(call -> {
            now.updateAndGet(instant -> instant.plusSeconds(15));
            return new ReceiptEvidence(0, "UNAVAILABLE", null, null);
        });

        var worker = worker();
        worker.process(first);

        verify(store).advance(first, 2, 2, false);
        verify(supplier, never()).observe(3);
        verify(store, times(2)).insertDiff(eq(first), any(), any(), anyLong(), any(), anyString());

        worker.process(second);

        verify(store).advance(second, 3, 1, true);
        verify(supplier, times(1)).observe(1);
        verify(supplier, times(1)).observe(2);
        verify(supplier, times(1)).observe(3);
        verify(store, times(1)).insertDiff(eq(second), any(), any(), anyLong(), any(), anyString());
        verifyNoInteractions(local);
    }

    private Observation order(long id, String mode) {
        return new Observation(id, 2, 0, "FULFILLED", mode, "DONE", 0,
                observedAt.plusSeconds(3600), observedAt);
    }
}
