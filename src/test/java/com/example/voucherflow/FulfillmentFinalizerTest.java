package com.example.voucherflow;

import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.campaign.model.CampaignRow;
import com.example.voucherflow.fulfillment.FulfillmentEventFactory;
import com.example.voucherflow.fulfillment.FulfillmentFinalizer;
import com.example.voucherflow.fulfillment.SupplierStore;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import com.example.voucherflow.inventory.InventoryService;
import com.example.voucherflow.issuance.mapper.OrderMapper;
import com.example.voucherflow.outbox.OutboxEventService;
import com.example.voucherflow.redemption.mapper.VoucherMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 验证履约终态确认对冲突证据、证据版本和过期任务租约的处理。 */
class FulfillmentFinalizerTest {
    private final CampaignMapper campaigns = mock(CampaignMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final VoucherMapper vouchers = mock(VoucherMapper.class);
    private final OutboxEventService outbox = mock(OutboxEventService.class);
    private final SupplierStore supplier = mock(SupplierStore.class);
    private final FulfillmentFinalizer finalizer = new FulfillmentFinalizer(campaigns, orders, inventory,
            vouchers, mock(FulfillmentEventFactory.class), outbox, supplier);

    @BeforeEach void campaignExists() {
        when(campaigns.lockById(2)).thenReturn(Optional.of(new CampaignRow()));
    }

    @Test void oppositeTerminalEvidenceConflictsEvenWhenItsVersionIsOlder() {
        fixture(order("FULFILLED", "DONE", null, null), "CALLBACK", null,
                result("REJECTED", 1L, null));
        when(supplier.receipt(10)).thenReturn(receipt(10, "CALLBACK", null,
                result("SUCCEEDED", 2L, "voucher")));

        assertThat(finalizer.applyReceipt(11).disposition()).isEqualTo("CONFLICT");

        verify(supplier).receiptState(11, "CONFLICT");
        verifyNoInteractions(inventory, orders, vouchers, outbox);
    }

    @Test void olderMatchingTerminalEvidenceIsIgnored() {
        fixture(order("FULFILLED", "DONE", null, null), "CALLBACK", null,
                result("SUCCEEDED", 1L, "voucher"));
        when(supplier.receipt(10)).thenReturn(receipt(10, "CALLBACK", null,
                result("SUCCEEDED", 2L, "voucher")));

        assertThat(finalizer.applyReceipt(11).disposition()).isEqualTo("IGNORED_OLD_VERSION");

        verify(supplier).receiptState(11, "IGNORED");
        verifyNoInteractions(inventory, orders, vouchers, outbox);
    }

    @Test void expiredWorkerCannotApplyAnOtherwiseValidSuccess() {
        var now = Instant.parse("2026-11-10T12:00:00Z");
        fixture(order("UNKNOWN", "RUNNING", "token", now), "WORKER", "token",
                result("SUCCEEDED", 3L, "voucher"));
        when(supplier.now()).thenReturn(now);

        assertThat(finalizer.applyReceipt(11).disposition()).isEqualTo("STALE_EXECUTOR");

        verify(supplier).receiptState(11, "STALE");
        verifyNoInteractions(inventory, orders, vouchers, outbox);
    }

    @Test void notFoundPreservesReservationAndAcceptedVersion() {
        fixture(order("UNKNOWN", "PAUSED", null, null), "CALLBACK", null,
                result("NOT_FOUND", null, null));

        assertThat(finalizer.applyReceipt(11).disposition()).isEqualTo("PROGRESS");

        verify(supplier).receiptState(11, "APPLIED");
        verify(supplier, never()).acceptVersion(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(inventory, orders, vouchers, outbox);
    }

    private void fixture(SupplierStore.Order order, String source, String token, SupplierProtocol.Result result) {
        when(supplier.find(1)).thenReturn(order);
        when(supplier.lock(1)).thenReturn(order);
        var receipt = receipt(11, source, token, result);
        when(supplier.receipt(11)).thenReturn(receipt);
        when(supplier.lockReceipt(11)).thenReturn(receipt);
    }

    private SupplierStore.Order order(String status, String jobState, String token, Instant leaseUntil) {
        return new SupplierStore.Order(1, 2, 3, "request", "simulator", "v1", "product", "subject",
                status, 4, Instant.MAX, jobState, token, leaseUntil, "QUERY", 1, 0, 2L, 10L, Instant.EPOCH);
    }

    private SupplierProtocol.Result result(String status, Long version, String voucher) {
        return new SupplierProtocol.Result("request", "product", "subject", status, version, voucher);
    }

    private SupplierStore.Receipt receipt(long id, String source, String token, SupplierProtocol.Result result) {
        return new SupplierStore.Receipt(id, 1, source, token, "PENDING", result);
    }
}
