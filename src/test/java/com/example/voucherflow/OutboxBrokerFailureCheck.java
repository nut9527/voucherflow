package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.notification.NotificationService;
import com.example.voucherflow.outbox.DomainEvent;
import com.example.voucherflow.outbox.EventCodec;
import com.example.voucherflow.outbox.OutboxDispatcher;
import com.example.voucherflow.outbox.OutboxLeaseService;
import com.example.voucherflow.outbox.RocketMqEventSender;
import com.example.voucherflow.support.IsolatedRocketMq;
import com.example.voucherflow.support.MySqlIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 验证真实消息 SDK 的故障边界；此测试环境未启用认证，不覆盖访问控制场景。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "voucherflow.messaging.enabled=false",
            "voucherflow.messaging.lease=7s",
            "voucherflow.messaging.request-timeout=2s"
        })
@DirtiesContext
class OutboxBrokerFailureCheck extends MySqlIntegrationTest {
    private static final IsolatedRocketMq BROKER = new IsolatedRocketMq();
    private static final ClientServiceProvider SDK = ClientServiceProvider.loadService();

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        BROKER.start();
        properties.add("voucherflow.messaging.endpoints", BROKER::endpoint);
    }

    @AfterAll
    static void closeBroker() {
        BROKER.close();
    }

    @Autowired CampaignService campaigns;
    @Autowired ClaimService claims;
    @Autowired EventCodec codec;
    @Autowired NotificationService notifications;
    @MockitoSpyBean OutboxLeaseService leases;

    @Test
    @Timeout(150)
    void offlineBrokerAndMissingTopicNeverMarkSent() throws Exception {
        DomainEvent offline = event();
        Instant detectedAt;
        long oldest;
        try (var producer = producer(EventCodec.NOTIFICATION_TOPIC)) {
            BROKER.pauseBroker();
            try {
                dispatch(producer);
                assertRetryable(offline);
                await(
                        () ->
                                number(
                                                "SELECT TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3)) FROM outbox_events WHERE id=?",
                                                offline.eventId())
                                        >= 1);
                oldest =
                        number(
                                "SELECT TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3)) FROM outbox_events WHERE id=?",
                                offline.eventId());
                detectedAt = Instant.now();
                assertThat(
                                number(
                                        "SELECT COUNT(*) FROM outbox_events WHERE state IN ('PENDING','SENDING')"))
                        .isEqualTo(1);
            } finally {
                BROKER.resumeBroker();
            }
            Instant restoredAt = Instant.now();
            try (var consumer = consumer()) {
                consumer.receive(1, Duration.ofSeconds(10));
                await(
                        () ->
                                Boolean.TRUE.equals(
                                        jdbc.queryForObject(
                                                "SELECT next_attempt_at<=UTC_TIMESTAMP(3) FROM outbox_events WHERE id=?",
                                                Boolean.class,
                                                offline.eventId())));
                dispatch(producer);
                consume(consumer, offline);
            }
            assertThat(state(offline)).isEqualTo("SENT");
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM outbox_events WHERE state IN ('PENDING','SENDING')"))
                    .isZero();
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM consumed_events WHERE event_id=?",
                                    offline.eventId()))
                    .isEqualTo(1);
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM notifications WHERE event_id=?",
                                    offline.eventId()))
                    .isEqualTo(1);
            Path evidence = Path.of("target", "broker-failure-check");
            Files.createDirectories(evidence);
            Files.writeString(
                    evidence.resolve("outage-recovery.json"),
                    tools.jackson.databind.json.JsonMapper.builder()
                            .findAndAddModules()
                            .build()
                            .writeValueAsString(
                                    Map.of(
                                            "eventId",
                                            offline.eventId(),
                                            "detectedAt",
                                            detectedAt,
                                            "restoredAt",
                                            restoredAt,
                                            "confirmedAt",
                                            Instant.now(),
                                            "trigger",
                                            "PENDING with send error and oldest age >= 1 second",
                                            "oldestPendingSecondsAtDetection",
                                            oldest,
                                            "pendingBefore",
                                            1,
                                            "pendingAfter",
                                            0,
                                            "consumedReceipts",
                                            1,
                                            "notifications",
                                            1)));
        } finally {
            postpone(offline);
        }

        DomainEvent missing = event();
        // 独立消息代理始终不创建目标路由，用于验证缺少主题的场景。
        try (var missingBroker = new IsolatedRocketMq()) {
            missingBroker.startWithTopics(EventCodec.FULFILLMENT_TOPIC);
            try (var producer =
                    SDK.newProducerBuilder()
                            .setClientConfiguration(config(missingBroker.endpoint()))
                            .setTopics(EventCodec.FULFILLMENT_TOPIC)
                            .setMaxAttempts(1)
                            .build()) {
                dispatch(producer);
                assertRetryable(missing);
            }
        } finally {
            postpone(missing);
        }
    }

    @Test
    @Timeout(90)
    void brokerAcceptanceBeforeFailedCompletionReplaysOriginalEventOnlyOnce() throws Exception {
        DomainEvent event = event();
        try (var producer = producer(EventCodec.NOTIFICATION_TOPIC);
                var consumer = consumer()) {
            consumer.receive(1, Duration.ofSeconds(10));
            doThrow(
                            new org.springframework.dao.DataAccessResourceFailureException(
                                    "injected completion outage"))
                    .doCallRealMethod()
                    .when(leases)
                    .complete(
                            argThat(value -> value.eventId().equals(event.eventId())),
                            eq(true),
                            isNull());
            dispatch(producer);
            assertThat(state(event)).isEqualTo("SENDING");
            var first = consume(consumer, event);
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM notifications WHERE event_id=?",
                                    event.eventId()))
                    .isEqualTo(1);
            await(
                    () ->
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT lease_until<=UTC_TIMESTAMP(3) FROM outbox_events WHERE id=?",
                                            Boolean.class,
                                            event.eventId())));
            assertThat(leases.recoverExpired()).isGreaterThanOrEqualTo(1);
            dispatch(producer);
            assertThat(state(event)).isEqualTo("SENT");
            var second = consume(consumer, event);
            assertThat(second).isNotEqualTo(first);
            assertThat(
                            number(
                                    "SELECT attempt_count FROM outbox_events WHERE id=?",
                                    event.eventId()))
                    .isEqualTo(2);
            assertThat(
                            codec.decode(
                                    jdbc.queryForObject(
                                            "SELECT payload FROM outbox_events WHERE id=?",
                                            String.class,
                                            event.eventId())))
                    .isEqualTo(event);
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM consumed_events WHERE event_id=?",
                                    event.eventId()))
                    .isEqualTo(1);
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM notifications WHERE event_id=?",
                                    event.eventId()))
                    .isEqualTo(1);
        } finally {
            doCallRealMethod().when(leases).complete(any(), anyBoolean(), any());
        }
    }

    @Test
    @Timeout(90)
    void rejectedPublisherSubmissionRecoversFromDatabaseLease() throws Exception {
        DomainEvent event = event();
        try (var producer = producer(EventCodec.NOTIFICATION_TOPIC);
                var consumer = consumer()) {
            consumer.receive(1, Duration.ofSeconds(10));
            var rejectedPool = Executors.newSingleThreadExecutor();
            rejectedPool.shutdown();
            var rejected = new OutboxDispatcher(leases, sender(producer), rejectedPool, 1);
            rejected.tick();
            rejected.stop();
            assertThat(state(event)).isEqualTo("SENDING");
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM consumed_events WHERE event_id=?",
                                    event.eventId()))
                    .isZero();
            await(
                    () ->
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT lease_until<=UTC_TIMESTAMP(3) FROM outbox_events WHERE id=?",
                                            Boolean.class,
                                            event.eventId())));
            leases.recoverExpired();
            dispatch(producer);
            assertThat(state(event)).isEqualTo("SENT");
            consume(consumer, event);
            assertThat(
                            number(
                                    "SELECT attempt_count FROM outbox_events WHERE id=?",
                                    event.eventId()))
                    .isEqualTo(2);
            assertThat(
                            number(
                                    "SELECT COUNT(*) FROM notifications WHERE event_id=?",
                                    event.eventId()))
                    .isEqualTo(1);
        }
    }

    private void assertRetryable(DomainEvent event) {
        assertThat(state(event)).isEqualTo("PENDING");
        assertThat(number("SELECT attempt_count FROM outbox_events WHERE id=?", event.eventId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sent_at IS NULL AND last_error IS NOT NULL FROM outbox_events WHERE id=?",
                                Boolean.class,
                                event.eventId()))
                .isTrue();
        assertThat(number("SELECT COUNT(*) FROM consumed_events WHERE event_id=?", event.eventId()))
                .isZero();
    }

    private void postpone(DomainEvent event) {
        jdbc.update(
                "UPDATE outbox_events SET next_attempt_at=TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(3)) WHERE id=?",
                event.eventId());
    }

    private void dispatch(Producer producer) throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var dispatcher = new OutboxDispatcher(leases, sender(producer), pool, 1);
            dispatcher.tick();
            dispatcher.stop();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private RocketMqEventSender sender(Producer producer) {
        return new RocketMqEventSender(SDK, producer, codec, (point, id) -> {});
    }

    private ClientConfiguration config() {
        return config(BROKER.endpoint());
    }

    private ClientConfiguration config(String endpoint) {
        return ClientConfiguration.newBuilder()
                .setEndpoints(endpoint)
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(2))
                .build();
    }

    private Producer producer(String topic) throws Exception {
        return SDK.newProducerBuilder()
                .setClientConfiguration(config())
                .setTopics(topic)
                .setMaxAttempts(1)
                .build();
    }

    private SimpleConsumer consumer() throws Exception {
        return SDK.newSimpleConsumerBuilder()
                .setClientConfiguration(config())
                .setConsumerGroup("vf_notification_worker")
                .setAwaitDuration(Duration.ofSeconds(5))
                .setSubscriptionExpressions(
                        Map.of(EventCodec.NOTIFICATION_TOPIC, new FilterExpression("*")))
                .build();
    }

    private String consume(SimpleConsumer consumer, DomainEvent event) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            for (var message : consumer.receive(1, Duration.ofSeconds(10))) {
                var received = codec.decode(message);
                assertThat(received).isEqualTo(event);
                notifications.consume(received);
                consumer.ack(message);
                return message.getMessageId().toString();
            }
        }
        throw new AssertionError("Expected event was not delivered");
    }

    private DomainEvent event() {
        long admin =
                jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
        String name = "send-failure-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(username,password_hash,role) VALUES(?,'unused','USER')", name);
        long user = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
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
                                                                    "发送边界验证",
                                                                    "",
                                                                    1,
                                                                    now.minusSeconds(10),
                                                                    now.plusSeconds(3600),
                                                                    now.plusSeconds(7200),
                                                                    FulfillmentMode.LOCAL,
                                                                    null,
                                                                    null,
                                                                    null))
                                                    .id());
                            campaigns.publish(id);
                            return id;
                        });
        var result = as(user, Role.USER, () -> claims.claim(campaign));
        var event =
                codec.decode(
                        jdbc.queryForObject(
                                "SELECT payload FROM outbox_events WHERE event_type='VoucherIssued.v1' AND aggregate_id=?",
                                String.class,
                                result.voucherId()));
        jdbc.update(
                "UPDATE outbox_events SET next_attempt_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(3)) WHERE id=?",
                event.eventId());
        return event;
    }

    private String state(DomainEvent event) {
        return jdbc.queryForObject(
                "SELECT state FROM outbox_events WHERE id=?", String.class, event.eventId());
    }

    private long number(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError("Expected broker/database state was not observed");
            Thread.sleep(50);
        }
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
}
