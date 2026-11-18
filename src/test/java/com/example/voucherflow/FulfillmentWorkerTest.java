package com.example.voucherflow;

import static org.mockito.Mockito.*;

import com.example.voucherflow.fulfillment.FulfillmentFinalizer;
import com.example.voucherflow.fulfillment.FulfillmentJobService;
import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.fulfillment.FulfillmentWorker;
import com.example.voucherflow.fulfillment.SupplierEvidenceService;
import com.example.voucherflow.fulfillment.SupplierReceiptStore;
import com.example.voucherflow.fulfillment.SupplierStore;
import com.example.voucherflow.fulfillment.supplier.HttpSupplierClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 验证单条回执应用或重试调度失败后，同批其他回执仍可继续处理。 */
class FulfillmentWorkerTest {
    private final FulfillmentFinalizer finalizer = mock(FulfillmentFinalizer.class);
    private final SupplierReceiptStore receipts = mock(SupplierReceiptStore.class);
    private final FulfillmentWorker worker = worker();

    private FulfillmentWorker worker() {
        var properties = new FulfillmentProperties();
        properties.setEnabled(true);
        return new FulfillmentWorker(
                properties,
                mock(FulfillmentJobService.class),
                mock(SupplierEvidenceService.class),
                mock(HttpSupplierClient.class),
                finalizer,
                mock(SupplierStore.class),
                receipts,
                (point, identity) -> {});
    }

    @AfterEach
    void close() {
        worker.close();
    }

    @Test
    void failedReceiptIsDeferredWithoutBlockingTheRestOfTheBatch() {
        when(receipts.pending(32)).thenReturn(List.of(1L, 2L));
        when(finalizer.applyReceipt(1))
                .thenThrow(new IllegalArgumentException("invalid association"));

        worker.maintain();

        verify(receipts).defer(1);
        verify(finalizer).applyReceipt(2);
        verify(receipts, never()).defer(2);
    }

    @Test
    void retrySchedulingFailureDoesNotSkipRemainingReceipts() {
        when(receipts.pending(32)).thenReturn(List.of(1L, 2L));
        when(finalizer.applyReceipt(1))
                .thenThrow(new IllegalArgumentException("invalid association"));
        doThrow(new DataAccessResourceFailureException("temporarily disconnected"))
                .when(receipts)
                .defer(1);

        worker.maintain();

        verify(finalizer).applyReceipt(2);
    }
}
