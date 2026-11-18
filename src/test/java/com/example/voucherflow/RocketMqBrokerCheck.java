package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.notification.NotificationService;
import com.example.voucherflow.outbox.DlqReplayTool;
import com.example.voucherflow.outbox.DomainEvent;
import com.example.voucherflow.outbox.EventCodec;
import com.example.voucherflow.support.IsolatedRocketMq;
import com.example.voucherflow.support.MySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
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

/** 使用隔离存储与消费组，验证真实消息代理上的履约消费、通知及死信重放。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "voucherflow.messaging.enabled=true",
            "voucherflow.messaging.publishing-enabled=true",
            "voucherflow.messaging.invisible-duration=10s",
            "voucherflow.messaging.consumer-threads=1",
            "voucherflow.fulfillment.enabled=true",
            "voucherflow.fulfillment.worker-enabled=false",
            "voucherflow.fulfillment.api-token=broker-check-only-secret-at-least-24",
            "voucherflow.fulfillment.callback-secret=broker-check-only-secret-at-least-24"
        })
@DirtiesContext
class RocketMqBrokerCheck extends MySqlIntegrationTest {
    private static final IsolatedRocketMq BROKER = new IsolatedRocketMq();

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
    @MockitoSpyBean NotificationService notificationSpy;

    @Test
    @Timeout(90)
    void blockedNotificationGroupDoesNotBlockDurableFulfillmentRegistration() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            entered.countDown();
                            assertThat(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                            return invocation.callRealMethod();
                        })
                .when(notificationSpy)
                .consume(org.mockito.ArgumentMatchers.any());
        try {
            long admin =
                    jdbc.queryForObject(
                            "SELECT id FROM users WHERE username='admin-demo'", Long.class);
            String name = "isolated-groups-" + UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO users(username,password_hash,role) VALUES(?,'unused','USER')",
                    name);
            long user =
                    jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
            long local = createCampaign(admin, FulfillmentMode.LOCAL);
            var issued = as(user, Role.USER, () -> claims.claim(local));
            assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long supplier = createCampaign(admin, FulfillmentMode.SUPPLIER);
            var accepted = as(user, Role.USER, () -> claims.claim(supplier));
            await(
                    () ->
                            "READY"
                                    .equals(
                                            jdbc.queryForObject(
                                                    "SELECT job_state FROM fulfillment_orders WHERE id=?",
                                                    String.class,
                                                    accepted.orderId())));
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM consumed_events c JOIN outbox_events e ON e.id=c.event_id "
                                            + "WHERE e.event_type='FulfillmentRequested.v1' AND e.aggregate_id=?",
                                    accepted.orderId()))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM notifications WHERE order_id=?",
                                    issued.orderId()))
                    .isZero();
            release.countDown();
            await(
                    () ->
                            count(
                                            "SELECT COUNT(*) FROM notifications WHERE order_id=?",
                                            issued.orderId())
                                    == 1);
        } finally {
            release.countDown();
            org.mockito.Mockito.doCallRealMethod()
                    .when(notificationSpy)
                    .consume(org.mockito.ArgumentMatchers.any());
        }
    }

    private long createCampaign(long admin, FulfillmentMode mode) {
        return as(
                admin,
                Role.ADMIN,
                () -> {
                    var now = Instant.now();
                    boolean remote = mode == FulfillmentMode.SUPPLIER;
                    long id =
                            Long.parseLong(
                                    campaigns
                                            .create(
                                                    new CampaignDtos.DraftRequest(
                                                            "消费组资源隔离",
                                                            "",
                                                            1,
                                                            now.minusSeconds(10),
                                                            now.plusSeconds(3600),
                                                            now.plusSeconds(7200),
                                                            mode,
                                                            remote ? "simulator" : null,
                                                            remote ? "COURSE" : null,
                                                            remote ? "v1" : null))
                                            .id());
                    campaigns.publish(id);
                    return id;
                });
    }

    @Test
    @Timeout(180)
    void nativeDeadLetterArchivesAndReplaysOriginalIdentity() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Injected consumer failure"))
                .when(notificationSpy)
                .consume(org.mockito.ArgumentMatchers.any());
        try {
            long admin =
                    jdbc.queryForObject(
                            "SELECT id FROM users WHERE username='admin-demo'", Long.class);
            String name = "dlq-" + UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO users(username,password_hash,role) VALUES(?,'unused','USER')",
                    name);
            long user =
                    jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
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
                                                                        "死信恢复验证",
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
            var archiveBody = new java.util.concurrent.atomic.AtomicReference<String>();
            var nativeId = new java.util.concurrent.atomic.AtomicReference<String>();
            long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
            while (archiveBody.get() == null && System.nanoTime() < deadline) {
                try {
                    String dump =
                            BROKER.admin(
                                    "printMsg",
                                    "-n",
                                    "namesrv:9876",
                                    "-t",
                                    "%DLQ%vf_notification_worker",
                                    "-d",
                                    "true",
                                    "-c",
                                    "UTF-8");
                    for (String line : dump.split("\\R")) {
                        var matcher =
                                java.util.regex.Pattern.compile("^MSGID: (\\S+) .* BODY: (.*)$")
                                        .matcher(line);
                        if (matcher.matches()
                                && codec.decode(matcher.group(2))
                                        .eventId()
                                        .equals(event.eventId())) {
                            nativeId.set(matcher.group(1));
                            archiveBody.set(matcher.group(2));
                        }
                    }
                } catch (IllegalStateException ignored) {
                    /* The native DLQ topic is created only after retry exhaustion. */ }
                if (archiveBody.get() == null) {
                    Thread.sleep(1000);
                }
            }
            assertThat(archiveBody.get())
                    .withFailMessage("Native broker retry budget did not reach DLQ")
                    .isNotNull();
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM consumed_events WHERE event_id=?",
                                    event.eventId()))
                    .isZero();
            assertThat(codec.decode(archiveBody.get())).isEqualTo(event);
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    archiveBody
                                                            .get()
                                                            .getBytes(StandardCharsets.UTF_8)));
            var archive =
                    new DlqReplayTool.Archive(
                            "vf_notification_worker",
                            nativeId.get(),
                            archiveBody.get(),
                            hash,
                            Instant.now());
            Path directory = Path.of("target", "broker-check", event.eventId());
            Files.createDirectories(directory);
            Path file = directory.resolve("dead-letter.jsonl");
            var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
            Files.writeString(file, json.writeValueAsString(archive) + "\n");
            org.mockito.Mockito.doCallRealMethod()
                    .when(notificationSpy)
                    .consume(org.mockito.ArgumentMatchers.any());
            long deliveriesBeforeReplay =
                    org.mockito.Mockito.mockingDetails(notificationSpy).getInvocations().stream()
                            .filter(
                                    call ->
                                            call.getMethod().getName().equals("consume")
                                                    && ((DomainEvent) call.getArgument(0))
                                                            .eventId()
                                                            .equals(event.eventId()))
                            .count();
            DlqReplayTool.main(new String[] {file.toString(), BROKER.endpoint()});
            await(
                    () ->
                            count(
                                            "SELECT COUNT(*) FROM notifications WHERE event_id=?",
                                            event.eventId())
                                    == 1);
            DlqReplayTool.main(new String[] {file.toString(), BROKER.endpoint()});
            await(
                    () ->
                            org.mockito.Mockito.mockingDetails(notificationSpy)
                                            .getInvocations()
                                            .stream()
                                            .filter(
                                                    call ->
                                                            call.getMethod()
                                                                            .getName()
                                                                            .equals("consume")
                                                                    && ((DomainEvent)
                                                                                    call
                                                                                            .getArgument(
                                                                                                    0))
                                                                            .eventId()
                                                                            .equals(
                                                                                    event
                                                                                            .eventId()))
                                            .count()
                                    >= deliveriesBeforeReplay + 2);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM notifications WHERE event_id=?",
                                    event.eventId()))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM consumed_events WHERE event_id=?",
                                    event.eventId()))
                    .isEqualTo(1);
        } finally {
            org.mockito.Mockito.doCallRealMethod()
                    .when(notificationSpy)
                    .consume(org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    void outboxTraversesBrokerAndDuplicateDeliveryCreatesOneNotification() throws Exception {
        long admin =
                jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
        String name = "broker-" + UUID.randomUUID();
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
                                                                    "消息链路验证",
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
        String payload =
                jdbc.queryForObject(
                        "SELECT payload FROM outbox_events WHERE event_type='VoucherIssued.v1' AND aggregate_id=?",
                        String.class,
                        result.voucherId());
        var event = codec.decode(payload);
        await(
                () ->
                        count(
                                        "SELECT COUNT(*) FROM notifications WHERE event_id=?",
                                        event.eventId())
                                == 1);
        await(
                () ->
                        count(
                                        "SELECT COUNT(*) FROM outbox_events WHERE id=? AND state='SENT'",
                                        event.eventId())
                                == 1);
        assertThat(count("SELECT COUNT(*) FROM consumed_events WHERE event_id=?", event.eventId()))
                .isEqualTo(1);

        var provider = ClientServiceProvider.loadService();
        var config =
                ClientConfiguration.newBuilder()
                        .setEndpoints(BROKER.endpoint())
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .enableSsl(false)
                        .build();
        try (var producer =
                provider.newProducerBuilder()
                        .setClientConfiguration(config)
                        .setTopics(EventCodec.NOTIFICATION_TOPIC)
                        .setMaxAttempts(1)
                        .build()) {
            var message =
                    provider.newMessageBuilder()
                            .setTopic(EventCodec.NOTIFICATION_TOPIC)
                            .setTag("VoucherIssuedV1")
                            .setKeys(event.eventId())
                            .addProperty("eventId", event.eventId())
                            .setBody(payload.getBytes(StandardCharsets.UTF_8))
                            .build();
            assertThat(producer.send(message)).isNotNull();
        }
        await(
                () ->
                        org.mockito.Mockito.mockingDetails(notificationSpy)
                                        .getInvocations()
                                        .stream()
                                        .filter(
                                                call ->
                                                        call.getMethod().getName().equals("consume")
                                                                && ((DomainEvent)
                                                                                call.getArgument(0))
                                                                        .eventId()
                                                                        .equals(event.eventId()))
                                        .count()
                                >= 2);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE event_id=?", event.eventId()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consumed_events WHERE event_id=?", event.eventId()))
                .isEqualTo(1);
    }

    private void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!condition.get()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Broker pipeline did not converge");
            }
            Thread.sleep(100);
        }
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
}
