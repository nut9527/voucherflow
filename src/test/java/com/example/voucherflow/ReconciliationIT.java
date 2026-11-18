package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.reconciliation.DifferenceClassifier;
import com.example.voucherflow.reconciliation.LocalReconciliationService;
import com.example.voucherflow.reconciliation.ReconciliationDtos.DiffDetail;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairReceipt;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairRequest;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RunRequest;
import com.example.voucherflow.reconciliation.ReconciliationService;
import com.example.voucherflow.reconciliation.ReconciliationStore;
import com.example.voucherflow.reconciliation.ReconciliationWorker;
import com.example.voucherflow.reconciliation.RepairOperation;
import com.example.voucherflow.reconciliation.RepairService;
import com.example.voucherflow.support.MySqlIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证对账扫描的游标、租约与账目快照，以及修复命令的幂等审计和事务回滚。 */
@TestPropertySource(
        properties = {
            "voucherflow.reconciliation.interval-ms=3600000",
            "voucherflow.reconciliation.batch-size=2"
        })
class ReconciliationIT extends MySqlIntegrationTest {
    @Autowired CampaignService campaigns;
    @Autowired ClaimService claims;
    @MockitoSpyBean ReconciliationStore store;
    @MockitoSpyBean LocalReconciliationService local;
    @Autowired ReconciliationWorker worker;
    @Autowired ReconciliationService service;
    @Autowired RepairService repairs;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(String username, Role role) {
        long id =
                jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        var principal = new LoginPrincipal(id, username, role, "unused");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
    }

    private long campaign() {
        return campaign(2);
    }

    private long campaign(int stock) {
        login("admin-demo", Role.ADMIN);
        var now = Instant.now();
        var id =
                Long.parseLong(
                        campaigns
                                .create(
                                        new CampaignDtos.DraftRequest(
                                                "reconciliation",
                                                "",
                                                stock,
                                                now.minusSeconds(60),
                                                now.plusSeconds(600),
                                                now.plusSeconds(1200),
                                                FulfillmentMode.LOCAL,
                                                null,
                                                null,
                                                null))
                                .id());
        campaigns.publish(id);
        return id;
    }

    @Test
    void scanRecordsFailedEventAndReplayIsAuditedAndIdempotent() {
        long campaign = campaign();
        login("user-demo", Role.USER);
        var order = claims.claim(campaign);
        String event =
                jdbc.queryForObject(
                        "SELECT id FROM outbox_events WHERE aggregate_type='USER_VOUCHER' AND aggregate_id=?",
                        String.class,
                        order.voucherId());
        jdbc.update("UPDATE outbox_events SET state='FAILED',attempt_count=10 WHERE id=?", event);
        login("admin-demo", Role.ADMIN);
        var run = service.create(new RunRequest(campaign));
        var lease = store.claim().orElseThrow();
        worker.process(lease);
        assertThat(service.detail(Long.parseLong(run.id())).state()).isEqualTo("COMPLETED");
        var diff = service.diffs(Long.parseLong(run.id()), new PageQuery()).items().getFirst();
        assertThat(diff.targetEventId()).isEqualTo(event);
        var preview = repairs.preview(Long.parseLong(diff.id()));
        assertThat(preview.allowedOperations()).containsExactly(RepairOperation.REPLAY_OUTBOX);
        var request =
                new RepairRequest(
                        UUID.randomUUID(),
                        preview.currentRevision(),
                        RepairOperation.REPLAY_OUTBOX,
                        "retry original event");
        var receipt = repairs.repair(Long.parseLong(diff.id()), request);
        assertThat(repairs.repair(Long.parseLong(diff.id()), request)).isEqualTo(receipt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM outbox_events WHERE id=?",
                                Integer.class,
                                event))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT manual_publish_granted_total FROM outbox_events WHERE id=?",
                                Integer.class,
                                event))
                .isEqualTo(3);
        assertThatThrownBy(
                        () ->
                                repairs.repair(
                                        Long.parseLong(diff.id()),
                                        new RepairRequest(
                                                request.commandId(),
                                                request.expectedRevision(),
                                                request.operation(),
                                                "changed")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.COMMAND_CONFLICT));
    }

    @Test
    void expiredScannerCannotAdvanceCursorOrWriteObservations() {
        long campaign = campaign();
        service.create(new RunRequest(campaign));
        var stale = store.claim().orElseThrow();
        jdbc.update(
                "UPDATE reconciliation_runs SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
                stale.id());
        var current = store.claim().orElseThrow();
        assertThat(current.token()).isNotEqualTo(stale.token());
        worker.process(stale);
        assertThat(store.run(stale.id()).state()).isEqualTo("RUNNING");
        worker.process(current);
        assertThat(store.run(current.id()).state()).isEqualTo("COMPLETED");
    }

    @Test
    void staleRevisionRejectsNewCommandWithoutGrantingCredits() {
        var diff = failedEventDiff();
        long id = Long.parseLong(diff.id());
        var preview = repairs.preview(id);
        jdbc.update(
                "UPDATE fulfillment_orders SET revision=revision+1 WHERE id=?",
                Long.parseLong(diff.orderId()));
        var request =
                new RepairRequest(
                        UUID.randomUUID(),
                        preview.currentRevision(),
                        RepairOperation.REPLAY_OUTBOX,
                        "retry original event");
        assertThatThrownBy(() -> repairs.repair(id, request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REVISION_CONFLICT));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM repair_commands WHERE command_id=?",
                                Integer.class,
                                request.commandId().toString()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT manual_publish_granted_total FROM outbox_events WHERE id=?",
                                Integer.class,
                                diff.targetEventId()))
                .isZero();
        assertThat(store.diff(id, false).state()).isEqualTo("OPEN");
    }

    @Test
    void failureAfterWritingResultRollsBackCommandEventCreditsAndDifference() {
        var diff = failedEventDiff();
        long id = Long.parseLong(diff.id());
        var request =
                new RepairRequest(
                        UUID.randomUUID(),
                        repairs.preview(id).currentRevision(),
                        RepairOperation.REPLAY_OUTBOX,
                        "retry original event");
        doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            throw new IllegalStateException("injected commit-boundary failure");
                        })
                .when(store)
                .completeCommand(any(RepairReceipt.class));
        assertThatThrownBy(() -> repairs.repair(id, request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM repair_commands WHERE command_id=?",
                                Integer.class,
                                request.commandId().toString()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM outbox_events WHERE id=?",
                                String.class,
                                diff.targetEventId()))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT manual_publish_granted_total FROM outbox_events WHERE id=?",
                                Integer.class,
                                diff.targetEventId()))
                .isZero();
        assertThat(store.diff(id, false).state()).isEqualTo("OPEN");
        doCallRealMethod().when(store).completeCommand(any(RepairReceipt.class));
        assertThat(repairs.repair(id, request).commandId())
                .isEqualTo(request.commandId().toString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM repair_commands WHERE command_id=? AND result IS NOT NULL",
                                Integer.class,
                                request.commandId().toString()))
                .isEqualTo(1);
    }

    @Test
    void expiredLeaseAtCommitRollsBackObservationsAndCursorTogether() {
        long campaign = campaign();
        login("user-demo", Role.USER);
        var order = claims.claim(campaign);
        login("admin-demo", Role.ADMIN);
        service.create(new RunRequest(campaign));
        var lease = store.claim().orElseThrow();
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            assertThat(store.owns(lease)).isTrue();
                                            store.insertDiff(
                                                    lease,
                                                    store.order(Long.parseLong(order.orderId())),
                                                    new DifferenceClassifier.Classification(
                                                            "AWAITING_CONFIRMATION",
                                                            "OBSERVING",
                                                            java.util.List.of()),
                                                    0,
                                                    null,
                                                    "{}");
                                            jdbc.update(
                                                    "UPDATE reconciliation_runs SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
                                                    lease.id());
                                            store.advance(
                                                    lease,
                                                    Long.parseLong(order.orderId()),
                                                    1,
                                                    true);
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.run(lease.id()).scannedCount()).isZero();
        assertThat(store.diffs(lease.id(), 0, 20)).isEmpty();
        worker.process(lease);
        assertThat(store.run(lease.id()).state()).isEqualTo("COMPLETED");
    }

    private DiffDetail failedEventDiff() {
        long campaign = campaign();
        login("user-demo", Role.USER);
        var order = claims.claim(campaign);
        String event =
                jdbc.queryForObject(
                        "SELECT id FROM outbox_events WHERE aggregate_type='USER_VOUCHER' AND aggregate_id=?",
                        String.class,
                        order.voucherId());
        jdbc.update("UPDATE outbox_events SET state='FAILED',attempt_count=10 WHERE id=?", event);
        login("admin-demo", Role.ADMIN);
        var run = service.create(new RunRequest(campaign));
        worker.process(store.claim().orElseThrow());
        return service.diffs(Long.parseLong(run.id()), new PageQuery()).items().getFirst();
    }

    @Test
    void scanFreezesCommittedOrderRangeAndLaterOrdersBelongToAnotherRun() {
        long campaign = campaign();
        login("user-demo", Role.USER);
        long first = Long.parseLong(claims.claim(campaign).orderId());
        login("admin-demo", Role.ADMIN);
        var run = service.create(new RunRequest(campaign));
        assertThat(run.upperOrderId()).isEqualTo(Long.toString(first));
        String username = "scan-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(username,password_hash,role) VALUES(?,'unused','USER')",
                username);
        login(username, Role.USER);
        long second = Long.parseLong(claims.claim(campaign).orderId());
        assertThat(second).isGreaterThan(first);
        var lease = store.claim().orElseThrow();
        assertThat(store.page(lease))
                .extracting(DifferenceClassifier.Observation::id)
                .containsExactly(first);
        worker.process(lease);
        assertThat(store.run(lease.id()).scannedCount()).isEqualTo(1);
        login("admin-demo", Role.ADMIN);
        var next = service.create(new RunRequest(campaign));
        assertThat(next.upperOrderId()).isEqualTo(Long.toString(second));
        worker.process(store.claim().orElseThrow());
    }

    @Test
    void utcObservationDoesNotDependOnJvmDefaultTimezone() {
        long campaign = campaign();
        login("user-demo", Role.USER);
        long id = Long.parseLong(claims.claim(campaign).orderId());
        var before = store.order(id);
        var original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"));
            assertThat(store.order(id).expiresAt()).isEqualTo(before.expiresAt());
            assertThat(java.time.Duration.between(store.now(), Instant.now()).abs())
                    .isLessThan(java.time.Duration.ofSeconds(2));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void pagedScanPersistsAccountingSnapshotAndFindsFailedEventsAcrossPages() {
        long campaign = campaign(3);
        for (int i = 0; i < 3; i++) {
            String username = "paged-" + UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO users(username,password_hash,role) VALUES(?,'unused','USER')",
                    username);
            login(username, Role.USER);
            var order = claims.claim(campaign);
            if (i != 1) {
                jdbc.update(
                        "UPDATE outbox_events SET state='FAILED' WHERE aggregate_type='USER_VOUCHER' AND aggregate_id=?",
                        order.voucherId());
            }
        }
        login("admin-demo", Role.ADMIN);
        long run = Long.parseLong(service.create(new RunRequest(campaign)).id());
        clearInvocations(local);
        worker.process(store.claim().orElseThrow());
        assertThat(store.run(run).state()).isEqualTo("READY");
        assertThat(store.run(run).scannedCount()).isEqualTo(2);
        var next = store.claim().orElseThrow();
        assertThat(next.accounting().valid()).isTrue();
        assertThat(next.accounting().observedAt()).isNotNull();
        worker.process(next);
        assertThat(store.run(run).state()).isEqualTo("COMPLETED");
        assertThat(store.run(run).scannedCount()).isEqualTo(3);
        assertThat(store.diffs(run, 0, 20))
                .hasSize(2)
                .allSatisfy(diff -> assertThat(diff.diffType()).isEqualTo("OUTBOX_PUBLISH_FAILED"));
        verify(local, times(1)).inspect(campaign);
    }
}
