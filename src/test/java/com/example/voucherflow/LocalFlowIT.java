package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import com.example.voucherflow.inventory.mapper.LedgerMapper;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.issuance.FulfillmentDtos;
import com.example.voucherflow.issuance.FulfillmentQueryService;
import com.example.voucherflow.issuance.mapper.OrderMapper;
import com.example.voucherflow.issuance.model.OrderRow;
import com.example.voucherflow.outbox.DomainEvent;
import com.example.voucherflow.outbox.OutboxEventService;
import com.example.voucherflow.reconciliation.LocalReconciliationService;
import com.example.voucherflow.redemption.RedemptionService;
import com.example.voucherflow.redemption.VoucherDtos;
import com.example.voucherflow.redemption.VoucherQueryService;
import com.example.voucherflow.redemption.mapper.RedemptionMapper;
import com.example.voucherflow.redemption.mapper.VoucherMapper;
import com.example.voucherflow.redemption.model.RedemptionRow;
import com.example.voucherflow.redemption.model.VoucherRow;
import com.example.voucherflow.support.MySqlIntegrationTest;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;

/** 验证本地领取到核销的事务闭环，覆盖并发库存、幂等回执、回滚与一致性快照。 */
class LocalFlowIT extends MySqlIntegrationTest {
    @Autowired ClaimService claims;
    @Autowired CampaignService campaigns;
    @Autowired RedemptionService redemptions;
    @Autowired FulfillmentQueryService orders;
    @Autowired VoucherQueryService vouchers;
    @Autowired LocalReconciliationService reconciliation;
    @MockitoSpyBean OrderMapper orderMapper;
    @MockitoSpyBean LedgerMapper ledgerMapper;
    @MockitoSpyBean VoucherMapper voucherMapper;
    @MockitoSpyBean RedemptionMapper redemptionMapper;
    @MockitoSpyBean OutboxEventService outbox;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unrelatedUniqueConflictIsNotReportedAsAnIdempotentClaim() {
        long campaign = campaign(1), user = user();
        var failure = new DuplicateKeyException("request number collision");
        doThrow(failure).when(orderMapper).insert(any(OrderRow.class));
        assertThatThrownBy(() -> as(user, Role.USER, () -> claims.claim(campaign)))
                .isSameAs(failure);
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", campaign))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=?", campaign))
                .isZero();
    }

    @Test
    void lockFailureIsRetriedOnceThenReturnsBusyWithoutReservingStock() {
        long campaign = campaign(1), user = user();
        doThrow(new CannotAcquireLockException("lock wait timed out"))
                .when(orderMapper)
                .insert(any(OrderRow.class));
        assertCode(() -> as(user, Role.USER, () -> claims.claim(campaign)), ErrorCode.SYSTEM_BUSY);
        verify(orderMapper, times(2)).insert(any(OrderRow.class));
        assertThat(count("SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=?", campaign))
                .isZero();
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", campaign))
                .isEqualTo(1);
    }

    @Test
    void claimPersistsAllFactsAndReplaySurvivesSoldOutAndDisabled() {
        long campaign = campaign(1), user = user();
        var first = as(user, Role.USER, () -> claims.claim(campaign));
        var repeat = as(user, Role.USER, () -> claims.claim(campaign));
        assertThat(first.replayed()).isFalse();
        assertThat(repeat.replayed()).isTrue();
        assertThat(repeat.orderId()).isEqualTo(first.orderId());
        assertThat(repeat.voucherId()).isEqualTo(first.voucherId());
        as(admin(), Role.ADMIN, () -> campaigns.disable(campaign));
        assertThat(as(user, Role.USER, () -> claims.claim(campaign)).orderId())
                .isEqualTo(first.orderId());
        var stats = as(admin(), Role.ADMIN, () -> reconciliation.checkCampaign(campaign));
        assertThat(stats.consistent()).isTrue();
        assertThat(stats.availableStock()).isZero();
        assertThat(stats.issuedStock()).isEqualTo(1);
        assertThat(stats.reserveCount()).isEqualTo(1);
        assertThat(stats.confirmCount()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type='USER_VOUCHER' AND aggregate_id=?",
                                first.voucherId()))
                .isEqualTo(1);
        assertThat(as(user, Role.USER, () -> orders.ownedByCampaign(campaign)).orderId())
                .isEqualTo(first.orderId());
    }

    @Test
    void sameUserLastSlotConcurrentRequestsReturnOneOrder() throws Exception {
        long campaign = campaign(1), user = user();
        var results = compete(24, index -> as(user, Role.USER, () -> claims.claim(campaign)));
        assertThat(results.stream().map(FulfillmentDtos.ClaimResult::orderId).distinct())
                .hasSize(1);
        assertThat(results.stream().filter(result -> !result.replayed())).hasSize(1);
        assertThat(
                        as(admin(), Role.ADMIN, () -> reconciliation.checkCampaign(campaign))
                                .consistent())
                .isTrue();
    }

    @Test
    void hundredUsersCompeteForTwentyWithoutOverselling() throws Exception {
        long campaign = campaign(20);
        var users = new ArrayList<Long>();
        for (int i = 0; i < 100; i++) {
            users.add(user());
        }
        var results =
                compete(
                        100,
                        index -> {
                            try {
                                as(users.get(index), Role.USER, () -> claims.claim(campaign));
                                return true;
                            } catch (BusinessException ex) {
                                assertThat(ex.code()).isEqualTo(ErrorCode.OUT_OF_STOCK);
                                return false;
                            }
                        });
        assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(20);
        var stats = as(admin(), Role.ADMIN, () -> reconciliation.checkCampaign(campaign));
        assertThat(stats.consistent()).isTrue();
        assertThat(stats.issuedStock()).isEqualTo(20);
        assertThat(stats.availableStock()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "ledger", "voucher", "outbox"})
    void writeFailureRollsBackEveryBusinessFact(String step) {
        long campaign = campaign(2), user = user();
        var failure = new IllegalStateException("test write failure");
        switch (step) {
            case "order" -> doThrow(failure).when(orderMapper).insert(any(OrderRow.class));
            case "ledger" ->
                    doThrow(failure).when(ledgerMapper).insert(anyLong(), anyLong(), any());
            case "voucher" -> doThrow(failure).when(voucherMapper).insert(any(VoucherRow.class));
            case "outbox" -> {
                OutboxEventService target = AopTestUtils.getUltimateTargetObject(outbox);
                doAnswer(
                                call -> {
                                    call.callRealMethod();
                                    DomainEvent event = call.getArgument(0);
                                    assertThat(
                                                    count(
                                                            "SELECT COUNT(*) FROM outbox_events WHERE id=?",
                                                            event.eventId()))
                                            .isEqualTo(1);
                                    throw failure;
                                })
                        .when(target)
                        .appendInCurrentTransaction(any());
            }
            default -> throw new IllegalArgumentException(step);
        }
        assertThatThrownBy(() -> as(user, Role.USER, () -> claims.claim(campaign)))
                .isSameAs(failure);
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", campaign))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=?", campaign))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM user_vouchers WHERE campaign_id=?", campaign))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE campaign_id=?", campaign))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM outbox_events WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.data.userId'))=?",
                                Long.toString(user)))
                .isZero();
    }

    @Test
    void outboxCannotWriteOutsideBusinessTransaction() {
        var event =
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        "VoucherIssued.v1",
                        1,
                        "USER_VOUCHER",
                        "test",
                        Instant.now(),
                        null,
                        Map.of("userId", "test"));
        assertThatThrownBy(() -> outbox.appendInCurrentTransaction(event))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE id=?", event.eventId()))
                .isZero();
    }

    @Test
    void repeatAndConcurrentRedemptionPreserveFirstReceipt() throws Exception {
        long campaign = campaign(1), user = user(), admin = admin();
        long voucher =
                Long.parseLong(as(user, Role.USER, () -> claims.claim(campaign)).voucherId());
        List<VoucherDtos.RedemptionReceipt> receipts =
                compete(12, index -> as(admin, Role.ADMIN, () -> redemptions.redeem(voucher)));
        var first =
                receipts.stream().filter(receipt -> !receipt.replayed()).findFirst().orElseThrow();
        assertThat(receipts.stream().filter(receipt -> !receipt.replayed())).hasSize(1);
        assertThat(receipts)
                .allSatisfy(
                        receipt -> {
                            assertThat(receipt.id()).isEqualTo(first.id());
                            assertThat(receipt.createdAt()).isEqualTo(first.createdAt());
                            assertThat(receipt.operatorId()).isEqualTo(first.operatorId());
                        });
        jdbc.update(
                "UPDATE user_vouchers SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",
                voucher);
        var replay = as(admin, Role.ADMIN, () -> redemptions.redeem(voucher));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", campaign)).isZero();
    }

    @Test
    void receiptFailureRollsBackVoucherUsage() {
        long campaign = campaign(1), user = user();
        long voucher =
                Long.parseLong(as(user, Role.USER, () -> claims.claim(campaign)).voucherId());
        doThrow(new IllegalStateException("receipt failed"))
                .when(redemptionMapper)
                .insert(any(RedemptionRow.class));
        assertThatThrownBy(() -> as(admin(), Role.ADMIN, () -> redemptions.redeem(voucher)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM user_vouchers WHERE id=?",
                                String.class,
                                voucher))
                .isEqualTo("UNUSED");
        assertThat(count("SELECT COUNT(*) FROM redemption_records WHERE voucher_id=?", voucher))
                .isZero();
        assertThat(
                        as(admin(), Role.ADMIN, () -> reconciliation.checkCampaign(campaign))
                                .consistent())
                .isTrue();
    }

    @Test
    void expiredUnusedVoucherCannotBeRedeemedAndDoesNotRestoreStock() {
        long campaign = campaign(1), user = user();
        long voucher =
                Long.parseLong(as(user, Role.USER, () -> claims.claim(campaign)).voucherId());
        jdbc.update(
                "UPDATE user_vouchers SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",
                voucher);
        assertCode(
                () -> as(admin(), Role.ADMIN, () -> redemptions.redeem(voucher)),
                ErrorCode.VOUCHER_EXPIRED);
        assertThat(as(user, Role.USER, () -> vouchers.ownedDetail(voucher)).status())
                .isEqualTo("EXPIRED");
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", campaign)).isZero();
    }

    @Test
    void ownedQueriesRejectOtherUsersAndUserCannotRedeem() {
        long campaign = campaign(1), owner = user(), other = user();
        var claim = as(owner, Role.USER, () -> claims.claim(campaign));
        long order = Long.parseLong(claim.orderId()), voucher = Long.parseLong(claim.voucherId());
        assertCode(
                () -> as(other, Role.USER, () -> orders.ownedDetail(order)), ErrorCode.NOT_FOUND);
        assertCode(
                () -> as(other, Role.USER, () -> orders.ownedByCampaign(campaign)),
                ErrorCode.NOT_FOUND);
        assertCode(
                () -> as(other, Role.USER, () -> vouchers.ownedDetail(voucher)),
                ErrorCode.NOT_FOUND);
        assertThat(as(other, Role.USER, () -> orders.ownedPage(new PageQuery(0, 20))).items())
                .isEmpty();
        assertThat(as(other, Role.USER, () -> vouchers.ownedPage(new PageQuery(0, 20))).items())
                .isEmpty();
        assertCode(
                () -> as(owner, Role.USER, () -> redemptions.redeem(voucher)), ErrorCode.FORBIDDEN);
    }

    @Test
    void reconciliationDetectsBrokenLinksEvenWhenAggregateCountsBalance() {
        long campaign = campaign(2), first = user(), second = user();
        var claim = as(first, Role.USER, () -> claims.claim(campaign));
        assertThat(
                        as(admin(), Role.ADMIN, () -> reconciliation.checkCampaign(campaign))
                                .consistent())
                .isTrue();
        jdbc.update(
                "UPDATE user_vouchers SET user_id=? WHERE id=?",
                second,
                Long.parseLong(claim.voucherId()));
        var stats = as(admin(), Role.ADMIN, () -> reconciliation.checkCampaign(campaign));
        assertThat(stats.differences().values()).allMatch(value -> value == 0);
        assertThat(stats.brokenLinks()).isPositive();
        assertThat(stats.consistent()).isFalse();
    }

    @Test
    void reconciliationReturnsConnectionToReadCommitted() throws Exception {
        long campaign = campaign(1);
        assertThat(
                        as(admin(), Role.ADMIN, () -> reconciliation.checkCampaign(campaign))
                                .consistent())
                .isTrue();
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getTransactionIsolation())
                    .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "DISABLED", "NOT_STARTED", "ENDED"})
    void rejectsNewClaimsOutsideTheActiveWindow(String state) {
        long campaign = campaign(1), user = user();
        ErrorCode expected =
                switch (state) {
                    case "DRAFT" -> {
                        jdbc.update("UPDATE campaigns SET status='DRAFT' WHERE id=?", campaign);
                        yield ErrorCode.NOT_FOUND;
                    }
                    case "DISABLED" -> {
                        as(admin(), Role.ADMIN, () -> campaigns.disable(campaign));
                        yield ErrorCode.CAMPAIGN_DISABLED;
                    }
                    case "NOT_STARTED" -> {
                        jdbc.update(
                                "UPDATE campaigns SET start_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR),end_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 HOUR),redeem_end_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 3 HOUR) WHERE id=?",
                                campaign);
                        yield ErrorCode.CAMPAIGN_NOT_STARTED;
                    }
                    case "ENDED" -> {
                        jdbc.update(
                                "UPDATE campaigns SET start_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 HOUR),end_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 HOUR) WHERE id=?",
                                campaign);
                        yield ErrorCode.CAMPAIGN_ENDED;
                    }
                    default -> throw new IllegalArgumentException(state);
                };
        assertCode(() -> as(user, Role.USER, () -> claims.claim(campaign)), expected);
        assertThat(count("SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=?", campaign))
                .isZero();
        assertThat(count("SELECT available_stock FROM campaigns WHERE id=?", campaign))
                .isEqualTo(1);
    }

    @Test
    void claimWaitingForCampaignLockUsesTimeAfterTheLock() throws Exception {
        long campaign = campaign(1), user = user();
        jdbc.update(
                "UPDATE campaigns SET end_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 SECOND) WHERE id=?",
                campaign);
        try (var lock = jdbc.getDataSource().getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try (var statement =
                    lock.prepareStatement("SELECT id FROM campaigns WHERE id=? FOR UPDATE")) {
                statement.setLong(1, campaign);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }
            var started = new CountDownLatch(1);
            var pending =
                    executor.submit(
                            () -> {
                                started.countDown();
                                return as(user, Role.USER, () -> claims.claim(campaign));
                            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                awaitDatabaseCondition(
                        "SELECT UTC_TIMESTAMP(3)>=end_at FROM campaigns WHERE id=?", campaign);
                assertThat(pending.isDone()).isFalse();
            } finally {
                lock.commit();
            }
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessException) ex.getCause()).code())
                                            .isEqualTo(ErrorCode.CAMPAIGN_ENDED));
        }
        assertThat(count("SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=?", campaign))
                .isZero();
    }

    @Test
    void redeemWaitingForVoucherLockUsesTimeAfterTheLock() throws Exception {
        long campaign = campaign(1), user = user(), admin = admin();
        long voucher =
                Long.parseLong(as(user, Role.USER, () -> claims.claim(campaign)).voucherId());
        jdbc.update(
                "UPDATE user_vouchers SET expires_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 SECOND) WHERE id=?",
                voucher);
        try (var lock = jdbc.getDataSource().getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try (var statement =
                    lock.prepareStatement("SELECT id FROM user_vouchers WHERE id=? FOR UPDATE")) {
                statement.setLong(1, voucher);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }
            var started = new CountDownLatch(1);
            var pending =
                    executor.submit(
                            () -> {
                                started.countDown();
                                return as(admin, Role.ADMIN, () -> redemptions.redeem(voucher));
                            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                awaitDatabaseCondition(
                        "SELECT UTC_TIMESTAMP(3)>=expires_at FROM user_vouchers WHERE id=?",
                        voucher);
                assertThat(pending.isDone()).isFalse();
            } finally {
                lock.commit();
            }
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessException) ex.getCause()).code())
                                            .isEqualTo(ErrorCode.VOUCHER_EXPIRED));
        }
        assertThat(count("SELECT COUNT(*) FROM redemption_records WHERE voucher_id=?", voucher))
                .isZero();
    }

    @Test
    void reconciliationUsesOneSnapshotWhileClaimsCommit() throws Exception {
        long campaign = campaign(40), admin = admin();
        var users = new ArrayList<Long>();
        for (int i = 0; i < 40; i++) {
            users.add(user());
        }
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writing =
                    executor.submit(
                            () -> {
                                for (long user : users) {
                                    as(user, Role.USER, () -> claims.claim(campaign));
                                }
                            });
            do {
                assertThat(
                                as(admin, Role.ADMIN, () -> reconciliation.checkCampaign(campaign))
                                        .consistent())
                        .isTrue();
            } while (!writing.isDone());
            writing.get(30, TimeUnit.SECONDS);
        }
        var result = as(admin, Role.ADMIN, () -> reconciliation.checkCampaign(campaign));
        assertThat(result.issuedStock()).isEqualTo(40);
        assertThat(result.consistent()).isTrue();
    }

    private void awaitDatabaseCondition(String sql, long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!jdbc.queryForObject(sql, Boolean.class, id)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Database deadline was not reached");
            }
            Thread.sleep(20);
        }
    }

    private long campaign(int stock) {
        return as(
                admin(),
                Role.ADMIN,
                () -> {
                    var now = Instant.now();
                    var created =
                            campaigns.create(
                                    new CampaignDtos.DraftRequest(
                                            "核验 " + UUID.randomUUID(),
                                            "",
                                            stock,
                                            now.minusSeconds(30),
                                            now.plusSeconds(3600),
                                            now.plusSeconds(7200),
                                            FulfillmentMode.LOCAL,
                                            null,
                                            null,
                                            null));
                    long id = Long.parseLong(created.id());
                    campaigns.publish(id);
                    return id;
                });
    }

    private long admin() {
        return jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
    }

    private long user() {
        String name = "flow-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(username,password_hash,role,created_at) VALUES(?,'test-only-unused-hash','USER',UTC_TIMESTAMP(3))",
                name);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private <T> T as(long id, Role role, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var principal = new LoginPrincipal(id, "test-" + id, role, null);
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void assertCode(Supplier<?> action, ErrorCode expected) {
        assertThatThrownBy(action::get)
                .isInstanceOfSatisfying(
                        BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    private <T> List<T> compete(int count, java.util.function.IntFunction<T> action)
            throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                tasks.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return action.apply(index);
                                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var task : tasks) {
                results.add(task.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
