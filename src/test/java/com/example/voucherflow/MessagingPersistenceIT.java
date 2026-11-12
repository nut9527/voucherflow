package com.example.voucherflow;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.notification.NotificationService;
import com.example.voucherflow.outbox.*;
import com.example.voucherflow.support.MySqlIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 验证消息租约、重放额度、消费去重及通知写入的数据库事务边界。 */
class MessagingPersistenceIT extends MySqlIntegrationTest {
    @Autowired OutboxLeaseService leases;
    @Autowired OutboxReplayService replays;
    @Autowired OutboxRecoveryQuery recovery;
    @Autowired ConsumedEventStore receipts;
    @Autowired NotificationService notifications;
    @Autowired CampaignService campaigns;
    @Autowired ClaimService claims;
    @Autowired ObjectMapper json;
    @Autowired EventCodec codec;
    @Autowired TransactionTemplate tx;

    @Test void expiredSenderCannotCompleteReclaimedEvent() {
        var event = event(); insert(event);
        var original = leases.claim(1).getFirst();
        assertThat(original.eventId()).isEqualTo(event.eventId());
        jdbc.update("UPDATE outbox_events SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?", event.eventId());
        assertThat(leases.complete(original, true, null)).isFalse();
        assertThat(leases.recoverExpired()).isGreaterThanOrEqualTo(1);
        prioritize(event.eventId());
        var replacement = leases.claim(1).getFirst();
        assertThat(replacement.leaseToken()).isNotEqualTo(original.leaseToken());
        assertThat(leases.complete(original, true, null)).isFalse();
        assertThat(leases.complete(replacement, true, null)).isTrue();
        assertThat(recovery.findByEventId(event.eventId()).orElseThrow().state()).isEqualTo("SENT");
    }

    @Test void manualReplayPreservesIdentityAndLifetimeAttemptBudget() {
        var event = event(); insert(event);
        jdbc.update("UPDATE outbox_events SET state='FAILED',attempt_count=10 WHERE id=?", event.eventId());
        tx.executeWithoutResult(status -> replays.replayInCurrentTransaction(1, event.eventId(), UUID.randomUUID().toString()));
        var view = recovery.findByEventId(event.eventId()).orElseThrow();
        assertThat(view.credits()).isEqualTo(3); assertThat(view.grantedTotal()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            prioritize(event.eventId());
            var lease = leases.claim(1).getFirst();
            assertThat(codec.decode(lease.payload())).isEqualTo(event);
            assertThat(lease.attemptCount()).isEqualTo(11 + i);
            leases.complete(lease, false, "BROKER_UNAVAILABLE");
        }
        assertThat(recovery.findByEventId(event.eventId()).orElseThrow().state()).isEqualTo("FAILED");
        tx.executeWithoutResult(status -> replays.replayInCurrentTransaction(1, event.eventId(), UUID.randomUUID().toString()));
        jdbc.update("UPDATE outbox_events SET state='FAILED',manual_publish_credits=0 WHERE id=?", event.eventId());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> replays.replayInCurrentTransaction(1,
                event.eventId(), UUID.randomUUID().toString()))).isInstanceOf(BusinessException.class);
        assertThat(recovery.findByEventId(event.eventId()).orElseThrow().grantedTotal()).isEqualTo(6);
    }

    @Test void consumptionRollsBackReceiptWithEffectAndRejectsChangedPayload() {
        var event = event();
        assertThatThrownBy(() -> receipts.consume("test-worker", event, () -> { throw new IllegalStateException("failure"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM consumed_events WHERE event_id=?", event.eventId())).isZero();
        var effects = new AtomicInteger();
        receipts.consume("test-worker", event, effects::incrementAndGet);
        receipts.consume("test-worker", event, effects::incrementAndGet);
        assertThat(effects.get()).isEqualTo(1);
        var changed = new DomainEvent(event.eventId(), event.eventType(), 1, event.aggregateType(), event.aggregateId(),
                event.occurredAt(), event.traceId(), Map.of("orderId", "1", "userId", "4", "voucherId", "3"));
        assertThatThrownBy(() -> receipts.consume("test-worker", changed, effects::incrementAndGet))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void notificationIsAtomicDeduplicatedAndOwnedByRecipient() {
        long admin = jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
        long user = user(); long another = user();
        long campaign = as(admin, Role.ADMIN, () -> {
            var now = Instant.now();
            long id = Long.parseLong(campaigns.create(new CampaignDtos.CreateRequest("消息验证", "", 2,
                    now.minusSeconds(10), now.plusSeconds(3600), now.plusSeconds(7200), FulfillmentMode.LOCAL,
                    null, null, null)).id());
            campaigns.publish(id); return id;
        });
        var order = as(user, Role.USER, () -> claims.claim(campaign));
        var event = codec.decode(jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type='VoucherIssued.v1' AND aggregate_id=?",
                String.class, order.voucherId()));
        notifications.consume(event); notifications.consume(event);
        var page = as(user, Role.USER, () -> notifications.ownedPage(new PageQuery()));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().orderId()).isEqualTo(order.orderId());
        assertThat(as(another, Role.USER, () -> notifications.ownedPage(new PageQuery())).items()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM consumed_events WHERE event_id=?", event.eventId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE event_id=?", event.eventId())).isEqualTo(1);
    }

    @Test void duplicateKeyFromBusinessEffectRollsBackReceiptAndPropagates() {
        var event = event();
        var failure = new DuplicateKeyException("business uniqueness conflict");
        assertThatThrownBy(() -> receipts.consume("effect-conflict", event, () -> { throw failure; }))
                .isSameAs(failure);
        assertThat(count("SELECT COUNT(*) FROM consumed_events WHERE event_id=?", event.eventId())).isZero();
        var effects = new AtomicInteger();
        receipts.consume("effect-conflict", event, effects::incrementAndGet);
        receipts.consume("effect-conflict", event, effects::incrementAndGet);
        assertThat(effects.get()).isEqualTo(1);
    }

    private DomainEvent event() {
        return new DomainEvent(UUID.randomUUID().toString(), "VoucherIssued.v1", 1, "USER_VOUCHER", "3", Instant.EPOCH,
                null, Map.of("orderId", "1", "userId", "2", "voucherId", "3"));
    }
    private void insert(DomainEvent event) {
        // 使用不同的聚合编号，将测试专用事件与真实券业务数据隔离。
        jdbc.update("INSERT INTO outbox_events(id,event_type,aggregate_type,aggregate_id,payload) VALUES(?,?,?,?,?)",
                event.eventId(), event.eventType(), "TEST_EVENT", event.eventId(), json.writeValueAsString(event));
        prioritize(event.eventId());
    }
    private void prioritize(String eventId) {
        jdbc.update("UPDATE outbox_events SET next_attempt_at=TIMESTAMPADD(YEAR,-20,UTC_TIMESTAMP(3)) WHERE id=?", eventId);
    }
    private long user() {
        String username = "message-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,role) VALUES(?,'unused','USER')", username);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private <T> T as(long id, Role role, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var principal = new LoginPrincipal(id, "test-" + id, role, null);
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try { return action.get(); } finally { SecurityContextHolder.setContext(previous); }
    }
}
