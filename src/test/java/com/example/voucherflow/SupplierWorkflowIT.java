package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.fulfillment.FulfillmentFinalizer;
import com.example.voucherflow.fulfillment.FulfillmentJobService;
import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.fulfillment.FulfillmentWorker;
import com.example.voucherflow.fulfillment.SupplierCallbackService;
import com.example.voucherflow.fulfillment.SupplierEvidenceService;
import com.example.voucherflow.fulfillment.SupplierReceiptStore;
import com.example.voucherflow.fulfillment.SupplierStore;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.support.MySqlIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证供应商发放、查单、回调、冲突证据与人工修复组成的完整履约流程。 */
@TestPropertySource(
        properties = {
            "voucherflow.fulfillment.enabled=true",
            "voucherflow.fulfillment.worker-enabled=false",
            "voucherflow.fulfillment.api-token=test-only-supplier-api-token",
            "voucherflow.fulfillment.callback-secret=test-only-supplier-callback-secret",
            "voucherflow.fulfillment.maintenance-ms=3600000",
            "voucherflow.reconciliation.interval-ms=3600000"
        })
class SupplierWorkflowIT extends MySqlIntegrationTest {
    @Autowired CampaignService campaigns;
    @Autowired ClaimService claims;
    @Autowired SupplierEvidenceService evidence;
    @Autowired FulfillmentFinalizer finalizer;
    @Autowired SupplierStore store;
    @Autowired SupplierReceiptStore receipts;
    @Autowired FulfillmentJobService jobs;
    @Autowired TransactionTemplate tx;
    @Autowired FulfillmentProperties properties;
    @Autowired com.example.voucherflow.reconciliation.RepairService repairs;
    @Autowired com.example.voucherflow.reconciliation.ReconciliationStore reconciliationStore;
    @Autowired com.example.voucherflow.reconciliation.ReconciliationWorker reconciliationWorker;

    @Autowired
    com.example.voucherflow.reconciliation.LocalReconciliationService localReconciliation;

    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired SupplierCallbackService callbacks;
    @Autowired FulfillmentWorker worker;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.voucherflow.outbox.MessagingProperties messaging;

    @org.junit.jupiter.api.BeforeEach
    void enableMessagingContract() {
        doReturn(true).when(messaging).enabled();
        doReturn(true).when(messaging).publishingEnabled();
    }

    @Test
    void pausedPublishingRejectsNewSupplierWorkButPreservesClaimReplay() {
        var now = Instant.now();
        long campaign =
                as(
                        admin(),
                        Role.ADMIN,
                        () ->
                                Long.parseLong(
                                        campaigns
                                                .create(
                                                        new CampaignDtos.DraftRequest(
                                                                "暂停发布",
                                                                "",
                                                                1,
                                                                now.minusSeconds(30),
                                                                now.plusSeconds(3600),
                                                                now.plusSeconds(7200),
                                                                FulfillmentMode.SUPPLIER,
                                                                "simulator",
                                                                "course",
                                                                "v1"))
                                                .id()));
        doReturn(false).when(messaging).publishingEnabled();
        assertThatThrownBy(() -> as(admin(), Role.ADMIN, () -> campaigns.publish(campaign)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ErrorCode.INVALID_CAMPAIGN_STATE));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM campaigns WHERE id=?", String.class, campaign))
                .isEqualTo("DRAFT");

        doReturn(true).when(messaging).publishingEnabled();
        as(admin(), Role.ADMIN, () -> campaigns.publish(campaign));
        long user =
                jdbc.queryForObject("SELECT id FROM users WHERE username='user-demo'", Long.class);
        doReturn(false).when(messaging).publishingEnabled();
        assertThatThrownBy(() -> as(user, Role.USER, () -> claims.claim(campaign)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.SYSTEM_BUSY));
        assertThat(count("SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=?", campaign))
                .isZero();
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", campaign))
                .isEqualTo(1);

        doReturn(true).when(messaging).publishingEnabled();
        var original = as(user, Role.USER, () -> claims.claim(campaign));
        doReturn(false).when(messaging).publishingEnabled();
        var replay = as(user, Role.USER, () -> claims.claim(campaign));
        assertThat(replay.orderId()).isEqualTo(original.orderId());
        assertThat(replay.replayed()).isTrue();
    }

    @Test
    void signedCallbackForUnknownOrderReturnsNotFoundWithoutReceipt() throws Exception {
        String eventId = UUID.randomUUID().toString();
        byte[] body =
                json.writeValueAsBytes(
                        new SupplierProtocol.Callback(
                                eventId,
                                UUID.randomUUID().toString(),
                                "course",
                                "user:missing",
                                "SUCCEEDED",
                                2L,
                                "unknown-voucher"));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        assertThat(
                        callbackHttp(
                                timestamp,
                                SupplierProtocol.sign(
                                        properties.getCallbackSecret(), timestamp, body),
                                body))
                .isEqualTo(404);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM supplier_receipts WHERE receipt_key=?",
                                "callback:" + eventId))
                .isZero();
    }

    @Test
    void poisonReceiptsYieldToLaterWorkAndRemainRetryable() {
        long id = order();
        var order = store.find(id);
        var result = result(order, "SUCCEEDED", 2L, "voucher-" + id);
        var poisoned = new java.util.ArrayList<Long>();
        for (int i = 0; i < 33; i++) {
            long receipt =
                    evidence.record(id, "poison:" + UUID.randomUUID(), "CALLBACK", null, result);
            poisoned.add(receipt);
            jdbc.update(
                    "UPDATE supplier_receipts SET product_code='wrong-product' WHERE id=?",
                    receipt);
        }
        long valid = evidence.record(id, "valid:" + UUID.randomUUID(), "CALLBACK", null, result);
        jdbc.update(
                """
                UPDATE supplier_receipts SET next_apply_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(3)) WHERE order_id=?
                """,
                id);

        worker.maintain();
        assertThat(store.find(id).status()).isEqualTo("PENDING");
        worker.maintain();
        assertThat(store.find(id).status()).isEqualTo("FULFILLED");
        assertThat(receipts.find(valid).applyState()).isEqualTo("APPLIED");
        assertThat(receipts.pending(100)).doesNotContainAnyElementsOf(poisoned);

        long repaired = poisoned.getFirst();
        jdbc.update(
                """
                UPDATE supplier_receipts SET product_code=?,next_apply_at=UTC_TIMESTAMP(3) WHERE id=?
                """,
                order.productCode(),
                repaired);
        worker.maintain();
        assertThat(receipts.find(repaired).applyState()).isEqualTo("APPLIED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='CONFIRM'",
                                id))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_vouchers WHERE order_id=?", id)).isEqualTo(1);
    }

    @Test
    void supplierSuccessAndDuplicateEvidenceConfirmExactlyOnce() {
        long id = order();
        var order = store.find(id);
        var result = result(order, "SUCCEEDED", 2L, "voucher-" + id);
        long receipt =
                evidence.record(id, "callback:" + UUID.randomUUID(), "CALLBACK", null, result);
        assertThat(finalizer.applyReceipt(receipt).disposition()).isEqualTo("APPLIED");
        long revision = store.find(id).revision();
        assertThat(finalizer.applyReceipt(receipt).disposition()).isEqualTo("REPLAY");
        assertThat(store.find(id).revision()).isEqualTo(revision);
        assertThat(count("SELECT COUNT(*) FROM user_vouchers WHERE order_id=?", id)).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='CONFIRM'",
                                id))
                .isEqualTo(1);
        assertThat(count("SELECT reserved_stock FROM campaigns WHERE id=?", order.campaignId()))
                .isZero();
    }

    @Test
    void terminalReceiptFromAnotherOrderBreaksReconciliationEvenWithBalancedCounters() {
        long first = order(), second = order();
        var firstOrder = store.find(first);
        var secondOrder = store.find(second);
        long firstReceipt =
                evidence.record(
                        first,
                        "first:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        result(firstOrder, "SUCCEEDED", 2L, "voucher-" + first));
        long secondReceipt =
                evidence.record(
                        second,
                        "second:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        result(secondOrder, "SUCCEEDED", 2L, "voucher-" + second));
        finalizer.applyReceipt(firstReceipt);
        finalizer.applyReceipt(secondReceipt);
        assertThat(localReconciliation.inspect(firstOrder.campaignId()).consistent()).isTrue();
        jdbc.update(
                "UPDATE stock_ledger SET source_receipt_id=? WHERE order_id=? AND entry_type='CONFIRM'",
                secondReceipt,
                first);
        var stats = localReconciliation.inspect(firstOrder.campaignId());
        assertThat(stats.consistent()).isFalse();
        assertThat(stats.brokenLinks()).isPositive();
        assertThat(stats.differences().values()).allMatch(value -> value == 0);
        assertThat(stats.issuedStock()).isEqualTo(1);
        assertThat(stats.reservedStock()).isZero();
    }

    @Test
    void instanceWithoutSupplierCapabilityCompletesScanWithUnavailableEvidence() {
        long id = order();
        var order = store.find(id);
        properties.setEnabled(false);
        try {
            assertThat(evidence.observe(id).status()).isEqualTo("UNAVAILABLE");
            long run = reconciliationStore.create(order.campaignId());
            var lease = reconciliationStore.claim().orElseThrow();
            assertThat(lease.id()).isEqualTo(run);
            reconciliationWorker.process(lease);
            assertThat(reconciliationStore.run(run).state()).isEqualTo("COMPLETED");
            var diff = reconciliationStore.diffs(run, 0, 20).getFirst();
            assertThat(diff.diffType()).isEqualTo("AWAITING_CONFIRMATION");
            assertThat(diff.evidence()).contains("UNAVAILABLE");
            var preview = as(admin(), Role.ADMIN, () -> repairs.preview(Long.parseLong(diff.id())));
            assertThat(preview.supplierStatus()).isEqualTo("UNAVAILABLE");
            assertThat(preview.allowedOperations()).isEmpty();
            assertThatThrownBy(() -> evidence.queryForRepair(id))
                    .isInstanceOfSatisfying(
                            com.example.voucherflow.common.BusinessException.class,
                            error ->
                                    assertThat(error.code())
                                            .isEqualTo(
                                                    com.example.voucherflow.common.ErrorCode
                                                            .SYSTEM_BUSY));
            assertThat(store.find(id).status()).isEqualTo("PENDING");
        } finally {
            properties.setEnabled(true);
        }
    }

    @Test
    void notFoundRetainsReservationAndOnlyExplicitRejectionReleases() {
        long id = order();
        var order = store.find(id);
        finalizer.applyReceipt(
                evidence.record(
                        id,
                        "query:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        result(order, "NOT_FOUND", null, null)));
        assertThat(count("SELECT reserved_stock FROM campaigns WHERE id=?", order.campaignId()))
                .isEqualTo(1);
        finalizer.applyReceipt(
                evidence.record(
                        id,
                        "reject:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        result(order, "REJECTED", 2L, null)));
        assertThat(store.find(id).status()).isEqualTo("FAILED");
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", order.campaignId()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_vouchers WHERE order_id=?", id)).isZero();
    }

    @Test
    void conflictingTerminalEvidenceCannotReleaseConfirmedInventory() {
        long id = order();
        var order = store.find(id);
        finalizer.applyReceipt(
                evidence.record(
                        id,
                        "success:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        result(order, "SUCCEEDED", 2L, "voucher-" + id)));
        var conflict =
                finalizer.applyReceipt(
                        evidence.record(
                                id,
                                "reject:" + UUID.randomUUID(),
                                "CALLBACK",
                                null,
                                result(order, "REJECTED", 3L, null)));
        assertThat(conflict.disposition()).isEqualTo("CONFLICT");
        assertThat(store.find(id).status()).isEqualTo("FULFILLED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='RELEASE'",
                                id))
                .isZero();
    }

    @Test
    void expiredWorkerCannotApplyButCallbackCanRecover() {
        long id = order();
        ready(id);
        var execution = jobs.claim(1).getFirst();
        jdbc.update(
                "UPDATE fulfillment_orders SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
                id);
        var result = result(execution.order(), "SUCCEEDED", 2L, "voucher-" + id);
        long receipt =
                evidence.record(
                        id, "worker:" + UUID.randomUUID(), "WORKER", execution.token(), result);
        assertThat(finalizer.applyReceipt(receipt).disposition()).isEqualTo("STALE_EXECUTOR");
        assertThat(store.find(id).status()).isEqualTo("UNKNOWN");
        jobs.recoverExpired();
        assertThat(store.find(id).nextAction()).isEqualTo("QUERY");
        finalizer.applyReceipt(
                evidence.record(id, "callback:" + UUID.randomUUID(), "CALLBACK", null, result));
        assertThat(store.find(id).status()).isEqualTo("FULFILLED");
    }

    @Test
    void expiredIdempotencyWindowOnlyPermitsQueryAndManualCreditIsBounded() {
        long id = order();
        ready(id);
        jdbc.update(
                "UPDATE fulfillment_orders SET idempotency_deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
                id);
        assertThat(jobs.claim(1).getFirst().action()).isEqualTo("QUERY");
        for (int i = 0; i < 3; i++) {
            jdbc.update(
                    "UPDATE fulfillment_orders SET job_state='PAUSED',attempt_count=12,manual_query_credits=0 WHERE id=?",
                    id);
            tx.executeWithoutResult(s -> jobs.requeryInCurrentTransaction(id));
            assertThat(jobs.claim(1).getFirst().action()).isEqualTo("QUERY");
        }
        jdbc.update("UPDATE fulfillment_orders SET job_state='PAUSED' WHERE id=?", id);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jobs.requeryInCurrentTransaction(id)))
                .isInstanceOf(com.example.voucherflow.common.BusinessException.class);
    }

    @Test
    void reusedReceiptKeyWithDifferentResultIsRejected() {
        long id = order();
        var order = store.find(id);
        String key = "callback:" + UUID.randomUUID();
        evidence.record(id, key, "CALLBACK", null, result(order, "ACCEPTED", 1L, null));
        assertThatThrownBy(
                        () ->
                                evidence.record(
                                        id,
                                        key,
                                        "CALLBACK",
                                        null,
                                        result(order, "REJECTED", 2L, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heldHttpEvidenceNeedsCommandAndConfirmationReplayPreservesAudit() throws Exception {
        long id = order();
        var order = store.find(id);
        try (var remote = new SupplierHttp(result(order, "SUCCEEDED", 2L, "voucher-" + id))) {
            var held = evidence.queryForRepair(id);
            assertThat(receipts.find(held.receiptId()).applyState()).isEqualTo("HELD_FOR_COMMAND");
            assertThatThrownBy(() -> finalizer.applyReceipt(held.receiptId()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.find(id).status()).isEqualTo("PENDING");
            long diff = diff(id, "REMOTE_SUCCESS_LOCAL_OPEN");
            var request =
                    new com.example.voucherflow.reconciliation.ReconciliationDtos.RepairRequest(
                            UUID.randomUUID(),
                            order.revision(),
                            com.example.voucherflow.reconciliation.RepairOperation.CONFIRM,
                            "供应商查单确认");
            var receipt = as(admin(), Role.ADMIN, () -> repairs.repair(diff, request));
            int calls = remote.calls.get();
            assertThat(as(admin(), Role.ADMIN, () -> repairs.repair(diff, request)))
                    .isEqualTo(receipt);
            assertThat(remote.calls.get()).isEqualTo(calls);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM stock_ledger l JOIN supplier_receipts r ON r.id=l.source_receipt_id JOIN repair_commands c ON c.command_id=l.repair_command_id WHERE l.order_id=? AND l.entry_type='CONFIRM' AND r.source='REPAIR_QUERY'",
                                    id))
                    .isEqualTo(1);
            var late =
                    finalizer.applyReceipt(
                            evidence.record(
                                    id,
                                    "late:" + UUID.randomUUID(),
                                    "CALLBACK",
                                    null,
                                    result(order, "REJECTED", 3L, null)));
            assertThat(late.disposition()).isEqualTo("CONFLICT");
            assertThat(store.find(id).status()).isEqualTo("FULFILLED");
        }
    }

    @Test
    void repairReleaseRequiresExplicitHttpRejection() throws Exception {
        long id = order();
        var order = store.find(id);
        long diff = diff(id, "REMOTE_REJECT_LOCAL_RESERVED");
        var request =
                new com.example.voucherflow.reconciliation.ReconciliationDtos.RepairRequest(
                        UUID.randomUUID(),
                        order.revision(),
                        com.example.voucherflow.reconciliation.RepairOperation.RELEASE,
                        "供应商明确拒绝");
        try (var remote = new SupplierHttp(result(order, "NOT_FOUND", null, null))) {
            assertThatThrownBy(() -> as(admin(), Role.ADMIN, () -> repairs.repair(diff, request)))
                    .isInstanceOfSatisfying(
                            com.example.voucherflow.common.BusinessException.class,
                            e ->
                                    assertThat(e.code())
                                            .isEqualTo(
                                                    com.example.voucherflow.common.ErrorCode
                                                            .REPAIR_NOT_ALLOWED));
            assertThat(count("SELECT reserved_stock FROM campaigns WHERE id=?", order.campaignId()))
                    .isEqualTo(1);
            remote.result = result(order, "REJECTED", 2L, null);
            var receipt = as(admin(), Role.ADMIN, () -> repairs.repair(diff, request));
            assertThat(as(admin(), Role.ADMIN, () -> repairs.repair(diff, request)))
                    .isEqualTo(receipt);
            assertThat(store.find(id).status()).isEqualTo("FAILED");
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='RELEASE' AND repair_command_id IS NOT NULL AND source_receipt_id IS NOT NULL",
                                    id))
                    .isEqualTo(1);
        }
    }

    @Test
    void lateCallbackInvalidatesRepairPreviewRevision() throws Exception {
        long id = order();
        var order = store.find(id);
        long diff = diff(id, "REMOTE_SUCCESS_LOCAL_OPEN");
        var result = result(order, "SUCCEEDED", 2L, "voucher-" + id);
        try (var remote = new SupplierHttp(result)) {
            var preview = as(admin(), Role.ADMIN, () -> repairs.preview(diff));
            finalizer.applyReceipt(
                    evidence.record(id, "callback:" + UUID.randomUUID(), "CALLBACK", null, result));
            var request =
                    new com.example.voucherflow.reconciliation.ReconciliationDtos.RepairRequest(
                            UUID.randomUUID(),
                            preview.currentRevision(),
                            com.example.voucherflow.reconciliation.RepairOperation.CONFIRM,
                            "复核后并发回调");
            assertThatThrownBy(() -> as(admin(), Role.ADMIN, () -> repairs.repair(diff, request)))
                    .isInstanceOfSatisfying(
                            com.example.voucherflow.common.BusinessException.class,
                            e ->
                                    assertThat(e.code())
                                            .isEqualTo(
                                                    com.example.voucherflow.common.ErrorCode
                                                            .REVISION_CONFLICT));
            assertThat(count("SELECT COUNT(*) FROM repair_commands WHERE order_id=?", id)).isZero();
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='CONFIRM'",
                                    id))
                    .isEqualTo(1);
        }
    }

    @Test
    void signedCallbackIsDurableDeduplicatedAndOldProgressCannotReverseSuccess() throws Exception {
        long id = order();
        var order = store.find(id);
        String eventId = UUID.randomUUID().toString();
        byte[] body =
                json.writeValueAsBytes(
                        new SupplierProtocol.Callback(
                                eventId,
                                order.requestNo(),
                                order.productCode(),
                                order.subjectRef(),
                                "SUCCEEDED",
                                2L,
                                "voucher-" + id));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = SupplierProtocol.sign(properties.getCallbackSecret(), timestamp, body);
        assertThat(callbackHttp(timestamp, signature, body)).isEqualTo(200);
        assertThat(callbackHttp(timestamp, signature, body)).isEqualTo(200);
        long receipt =
                jdbc.queryForObject(
                        "SELECT id FROM supplier_receipts WHERE order_id=? AND receipt_key=?",
                        Long.class,
                        id,
                        "callback:" + eventId);
        assertThat(store.find(id).status()).isEqualTo("PENDING");
        finalizer.applyReceipt(receipt);
        var old =
                finalizer.applyReceipt(
                        evidence.record(
                                id,
                                "old:" + UUID.randomUUID(),
                                "CALLBACK",
                                null,
                                result(order, "ACCEPTED", 1L, null)));
        assertThat(old.disposition()).isEqualTo("IGNORED_OLD_VERSION");
        assertThat(count("SELECT COUNT(*) FROM user_vouchers WHERE order_id=?", id)).isEqualTo(1);
        assertThat(callbackHttp(timestamp, "invalid", body)).isEqualTo(403);
        String expired = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        assertThat(
                        callbackHttp(
                                expired,
                                SupplierProtocol.sign(
                                        properties.getCallbackSecret(), expired, body),
                                body))
                .isEqualTo(403);
        byte[] changed =
                json.writeValueAsBytes(
                        new SupplierProtocol.Callback(
                                eventId,
                                order.requestNo(),
                                order.productCode(),
                                order.subjectRef(),
                                "REJECTED",
                                3L,
                                null));
        assertThat(
                        callbackHttp(
                                timestamp,
                                SupplierProtocol.sign(
                                        properties.getCallbackSecret(), timestamp, changed),
                                changed))
                .isEqualTo(409);
    }

    @Test
    void concurrentQueryAndCallbackConfirmOnce() throws Exception {
        long id = order();
        ready(id);
        var execution = jobs.claim(1).getFirst();
        var order = store.find(id);
        var result = result(order, "SUCCEEDED", 2L, "voucher-" + id);
        long first =
                evidence.record(
                        id, "query:" + UUID.randomUUID(), "WORKER", execution.token(), result);
        long second =
                evidence.record(id, "callback:" + UUID.randomUUID(), "CALLBACK", null, result);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return finalizer.applyReceipt(first);
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return finalizer.applyReceipt(second);
                            });
            start.countDown();
            var dispositions =
                    java.util.List.of(
                            a.get(10, java.util.concurrent.TimeUnit.SECONDS).disposition(),
                            b.get(10, java.util.concurrent.TimeUnit.SECONDS).disposition());
            assertThat(dispositions)
                    .contains("APPLIED")
                    .allMatch(
                            value ->
                                    java.util.Set.of("APPLIED", "REPLAY", "STALE_EXECUTOR")
                                            .contains(value));
        }
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='CONFIRM'",
                                id))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_vouchers WHERE order_id=?", id)).isEqualTo(1);
    }

    @Test
    void signedLowerVersionOppositeTerminalCreatesReviewWithoutReleasingInventory()
            throws Exception {
        long id = order();
        var order = store.find(id);
        finalizer.applyReceipt(
                evidence.record(
                        id,
                        "success:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        result(order, "SUCCEEDED", 2L, "voucher-" + id)));
        String eventId = UUID.randomUUID().toString();
        byte[] body =
                json.writeValueAsBytes(
                        new SupplierProtocol.Callback(
                                eventId,
                                order.requestNo(),
                                order.productCode(),
                                order.subjectRef(),
                                "REJECTED",
                                1L,
                                null));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        assertThat(
                        callbackHttp(
                                timestamp,
                                SupplierProtocol.sign(
                                        properties.getCallbackSecret(), timestamp, body),
                                body))
                .isEqualTo(200);
        long receipt =
                jdbc.queryForObject(
                        "SELECT id FROM supplier_receipts WHERE receipt_key=?",
                        Long.class,
                        "callback:" + eventId);
        assertThat(finalizer.applyReceipt(receipt).disposition()).isEqualTo("CONFLICT");
        assertThat(receipts.find(receipt).applyState()).isEqualTo("CONFLICT");
        long run = reconciliationStore.create(order.campaignId());
        reconciliationWorker.process(reconciliationStore.claim().orElseThrow());
        var diff = reconciliationStore.diffs(run, 0, 20).getFirst();
        assertThat(diff.diffType()).isEqualTo("TERMINAL_CONFLICT");
        assertThat(diff.state()).isEqualTo("REVIEW_ONLY");
        assertThat(
                        as(admin(), Role.ADMIN, () -> repairs.preview(Long.parseLong(diff.id())))
                                .allowedOperations())
                .isEmpty();
        assertThat(store.find(id).status()).isEqualTo("FULFILLED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='RELEASE'",
                                id))
                .isZero();
        assertThat(localReconciliation.inspect(order.campaignId()).consistent()).isTrue();
    }

    @Test
    void queryWithDifferentVoucherForSameSuccessfulOrderRequiresReview() throws Exception {
        long id = order();
        var order = store.find(id);
        finalizer.applyReceipt(
                evidence.record(
                        id,
                        "success:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        result(order, "SUCCEEDED", 2L, "voucher-" + id)));
        try (var remote = new SupplierHttp(result(order, "SUCCEEDED", 2L, "different-voucher"))) {
            long run = reconciliationStore.create(order.campaignId());
            reconciliationWorker.process(reconciliationStore.claim().orElseThrow());
            var diff = reconciliationStore.diffs(run, 0, 20).getFirst();
            assertThat(diff.diffType()).isEqualTo("TERMINAL_CONFLICT");
            assertThat(diff.state()).isEqualTo("REVIEW_ONLY");
            assertThat(localReconciliation.inspect(order.campaignId()).consistent()).isTrue();
        }
    }

    @Test
    void repairOverHttpUsesFreshEvidenceAndReplaysOriginalCommand() throws Exception {
        long id = order();
        var order = store.find(id);
        try (var remote = new SupplierHttp(result(order, "SUCCEEDED", 2L, "voucher-" + id));
                var adminApi = new AdminHttp()) {
            var run =
                    adminApi.call(
                            "POST",
                            "/api/admin/reconciliation/runs",
                            java.util.Map.of("campaignId", order.campaignId()),
                            202);
            long runId = run.path("id").asLong();
            reconciliationWorker.process(reconciliationStore.claim().orElseThrow());
            var differences =
                    adminApi.call(
                            "GET", "/api/admin/reconciliation/runs/" + runId + "/diffs", null, 200);
            var difference = differences.path("items").get(0);
            String diffId = difference.path("id").asText();
            assertThat(difference.path("diffType").asText()).isEqualTo("REMOTE_SUCCESS_LOCAL_OPEN");
            var preview =
                    adminApi.call(
                            "GET",
                            "/api/admin/reconciliation/diffs/" + diffId + "/repair-preview",
                            null,
                            200);
            assertThat(preview.path("supplierStatus").asText()).isEqualTo("SUCCEEDED");
            assertThat(preview.path("allowedOperations").get(0).asText()).isEqualTo("CONFIRM");
            var command =
                    java.util.Map.of(
                            "commandId",
                            UUID.randomUUID().toString(),
                            "expectedRevision",
                            preview.path("currentRevision").asLong(),
                            "operation",
                            "CONFIRM",
                            "reason",
                            "供应商查单确认后恢复本地履约");
            var receipt =
                    adminApi.call(
                            "POST",
                            "/api/admin/reconciliation/diffs/" + diffId + "/repair",
                            command,
                            200);
            int calls = remote.calls.get();
            var replay =
                    adminApi.call(
                            "POST",
                            "/api/admin/reconciliation/diffs/" + diffId + "/repair",
                            command,
                            200);
            assertThat(replay).isEqualTo(receipt);
            assertThat(remote.calls.get()).isEqualTo(calls);
            var stats =
                    adminApi.call(
                            "GET",
                            "/api/admin/campaigns/" + order.campaignId() + "/stats",
                            null,
                            200);
            assertThat(stats.path("consistent").asBoolean()).isTrue();
            assertThat(stats.path("issuedStock").asInt()).isEqualTo(1);
            assertThat(stats.path("reservedStock").asInt()).isZero();
            assertThat(count("SELECT COUNT(*) FROM repair_commands WHERE order_id=?", id))
                    .isEqualTo(1);
            java.nio.file.Path output =
                    java.nio.file.Path.of("target", "repair-demo", "result.json");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(
                    output,
                    json.writeValueAsString(
                            java.util.Map.of(
                                    "orderId",
                                    id,
                                    "runId",
                                    runId,
                                    "difference",
                                    difference,
                                    "preview",
                                    preview,
                                    "receipt",
                                    receipt,
                                    "commandReplayMatched",
                                    true,
                                    "supplierHttpQueries",
                                    calls,
                                    "inventory",
                                    stats)));
        }
    }

    private class AdminHttp implements AutoCloseable {
        final java.net.http.HttpClient http =
                java.net.http.HttpClient.newBuilder()
                        .cookieHandler(
                                new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
                        .build();
        String csrfHeader, csrfToken;

        AdminHttp() throws Exception {
            csrf();
            var response =
                    http.send(
                            request("/api/auth/login")
                                    .header(csrfHeader, csrfToken)
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(
                                            java.net.http.HttpRequest.BodyPublishers.ofString(
                                                    "username=admin-demo&password=test-only-Admin-123"))
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            csrf();
        }

        void csrf() throws Exception {
            var token = call("GET", "/api/auth/csrf", null, 200);
            csrfHeader = token.path("headerName").asText();
            csrfToken = token.path("token").asText();
        }

        tools.jackson.databind.JsonNode call(String method, String path, Object body, int expected)
                throws Exception {
            var request = request(path);
            if ("GET".equals(method)) request.GET();
            else
                request.header(csrfHeader, csrfToken)
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                        body == null ? "" : json.writeValueAsString(body)));
            var response =
                    http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method + " " + path).isEqualTo(expected);
            return json.readTree(response.body()).path("data");
        }

        java.net.http.HttpRequest.Builder request(String path) {
            return java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://127.0.0.1:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(10));
        }

        public void close() throws Exception {
            call("POST", "/api/auth/logout", null, 200);
        }
    }

    private int callbackHttp(String timestamp, String signature, byte[] body) throws Exception {
        return java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/supplier/callbacks"))
                                .timeout(java.time.Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .header("X-Supplier-Timestamp", timestamp)
                                .header("X-Supplier-Signature", signature)
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private long admin() {
        return jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
    }

    private long diff(long orderId, String type) {
        return tx.execute(
                s -> {
                    var order = store.find(orderId);
                    jdbc.update(
                            "INSERT INTO reconciliation_runs(campaign_id,cutoff_at,state) VALUES(?,UTC_TIMESTAMP(3),'COMPLETED')",
                            order.campaignId());
                    long run = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    jdbc.update(
                            "INSERT INTO reconciliation_diffs(run_id,order_id,diff_type,observed_revision,evidence,state) VALUES(?,?,?,?,JSON_OBJECT(),'OPEN')",
                            run,
                            orderId,
                            type,
                            order.revision());
                    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                });
    }

    private class SupplierHttp implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        final String prior = properties.getBaseUrl();
        volatile SupplierProtocol.Result result;

        SupplierHttp(SupplierProtocol.Result result) throws Exception {
            this.result = result;
            server =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/supplier/fulfillments/",
                    exchange -> {
                        calls.incrementAndGet();
                        if (!"GET".equals(exchange.getRequestMethod())
                                || !"Bearer test-only-supplier-api-token"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization"))) {
                            exchange.sendResponseHeaders(403, -1);
                            exchange.close();
                            return;
                        }
                        byte[] body = json.writeValueAsBytes(this.result);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.setExecutor(executor);
            server.start();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        }

        public void close() {
            properties.setBaseUrl(prior);
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private SupplierProtocol.Result result(
            SupplierStore.Order order, String status, Long version, String voucher) {
        return new SupplierProtocol.Result(
                order.requestNo(),
                order.productCode(),
                order.subjectRef(),
                status,
                version,
                voucher);
    }

    private void ready(long id) {
        jdbc.update("UPDATE fulfillment_orders SET job_state='READY' WHERE id=?", id);
    }

    private long order() {
        long admin =
                jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
        long campaign =
                as(
                        admin,
                        Role.ADMIN,
                        () -> {
                            var now = Instant.now();
                            long id =
                                    Long.parseLong(
                                            campaigns
                                                    .create(
                                                            new CampaignDtos.DraftRequest(
                                                                    "供应商核验 " + UUID.randomUUID(),
                                                                    "",
                                                                    1,
                                                                    now.minusSeconds(30),
                                                                    now.plusSeconds(3600),
                                                                    now.plusSeconds(7200),
                                                                    FulfillmentMode.SUPPLIER,
                                                                    "simulator",
                                                                    "course",
                                                                    "v1"))
                                                    .id());
                            campaigns.publish(id);
                            return id;
                        });
        String name = "supplier-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(username,password_hash,role) VALUES(?,'unused-test-hash','USER')",
                name);
        long user = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        return as(user, Role.USER, () -> Long.parseLong(claims.claim(campaign).orderId()));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private <T> T as(long id, Role role, Supplier<T> action) {
        var prior = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var principal = new LoginPrincipal(id, "test-" + id, role, null);
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(prior);
        }
    }
}
