package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.fulfillment.FulfillmentFinalizer;
import com.example.voucherflow.fulfillment.FulfillmentJobService;
import com.example.voucherflow.fulfillment.FulfillmentProperties;
import com.example.voucherflow.fulfillment.FulfillmentWorker;
import com.example.voucherflow.fulfillment.SupplierEvidenceService;
import com.example.voucherflow.fulfillment.SupplierReceiptStore;
import com.example.voucherflow.fulfillment.SupplierStore;
import com.example.voucherflow.fulfillment.supplier.HttpSupplierClient;
import com.example.voucherflow.fulfillment.supplier.SupplierProtocol;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.issuance.ClaimService;
import com.example.voucherflow.support.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证履约并发额度、租约恢复、回执处理及停机时在途任务的收尾行为。 */
@TestPropertySource(
        properties = {
            "voucherflow.fulfillment.enabled=true",
            "voucherflow.fulfillment.worker-enabled=false",
            "voucherflow.fulfillment.api-token=test-only-supplier-api-token",
            "voucherflow.fulfillment.callback-secret=test-only-supplier-callback-secret",
            "voucherflow.fulfillment.poll-ms=3600000",
            "voucherflow.fulfillment.maintenance-ms=3600000",
            "voucherflow.fulfillment.idempotency-seconds=30",
            "voucherflow.fulfillment.lease-seconds=10",
            "voucherflow.fulfillment.max-attempts=2",
            "voucherflow.reconciliation.interval-ms=3600000"
        })
class SupplierCapacityIT extends MySqlIntegrationTest {
    @Autowired CampaignService campaigns;
    @Autowired ClaimService claims;
    @Autowired FulfillmentProperties properties;
    @Autowired SupplierStore store;
    @Autowired SupplierReceiptStore receipts;
    @Autowired SupplierEvidenceService evidence;
    @Autowired HttpSupplierClient client;
    @Autowired FulfillmentFinalizer finalizer;
    @MockitoSpyBean FulfillmentJobService jobs;
    @MockitoSpyBean com.example.voucherflow.outbox.MessagingProperties messaging;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void configure() {
        doReturn(true).when(messaging).enabled();
        doReturn(true).when(messaging).publishingEnabled();
        properties.setIdempotencySeconds(30);
    }

    @AfterEach
    void stopOutstandingFixtures() {
        for (long id : created)
            jdbc.update(
                    "UPDATE fulfillment_orders SET job_state='PAUSED',execution_token=NULL,lease_until=NULL WHERE id=? AND status IN ('PENDING','UNKNOWN')",
                    id);
        SecurityContextHolder.clearContext();
    }

    @Test
    void naturallyExpiredFrozenWindowOnlyQueriesThenPausesWithoutRelease() throws Exception {
        long id = order();
        var frozen = store.find(id).deadline();
        properties.setIdempotencySeconds(86400);
        try (var remote = new Remote(false);
                var worker = new ManagedWorker()) {
            await(() -> !store.now().isBefore(frozen), Duration.ofSeconds(23));
            worker.value.tick();
            await(() -> "READY".equals(store.find(id).jobState()), Duration.ofSeconds(4));
            await(
                    () ->
                            jdbc.queryForObject(
                                    "SELECT next_run_at<=UTC_TIMESTAMP(3) FROM fulfillment_orders WHERE id=?",
                                    Boolean.class,
                                    id),
                    Duration.ofSeconds(4));
            worker.value.tick();
            await(() -> "PAUSED".equals(store.find(id).jobState()), Duration.ofSeconds(4));
            assertThat(remote.issues.get()).isZero();
            assertThat(remote.queries.get()).isEqualTo(2);
            assertThat(store.find(id).deadline()).isEqualTo(frozen);
            assertThat(store.find(id).status()).isEqualTo("UNKNOWN");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT reserved_stock FROM campaigns WHERE id=?",
                                    Integer.class,
                                    store.find(id).campaignId()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stock_ledger WHERE order_id=? AND entry_type='RELEASE'",
                                    Integer.class,
                                    id))
                    .isZero();
        }
    }

    @Test
    void fullHttpCapacityStillAppliesReceiptsAndRecoversExpiredLease() throws Exception {
        long expired = order();
        var abandoned = jobs.claim(1).getFirst();
        assertThat(abandoned.order().id()).isEqualTo(expired);
        await(
                () -> !store.now().isBefore(store.find(expired).leaseUntil()),
                Duration.ofSeconds(12));
        long first = order(), second = order(), callback = order();
        jdbc.update("UPDATE fulfillment_orders SET job_state='WAITING_EVENT' WHERE id=?", callback);
        var callbackOrder = store.find(callback);
        long receipt =
                evidence.record(
                        callback,
                        "capacity:" + UUID.randomUUID(),
                        "CALLBACK",
                        null,
                        new SupplierProtocol.Result(
                                callbackOrder.requestNo(),
                                callbackOrder.productCode(),
                                callbackOrder.subjectRef(),
                                "SUCCEEDED",
                                2L,
                                "voucher-" + callback));
        try (var remote = new Remote(true);
                var worker = new ManagedWorker()) {
            worker.value.tick();
            assertThat(remote.started.await(3, TimeUnit.SECONDS)).isTrue();
            var pool = worker.pool();
            var slots = worker.slots();
            assertThat(pool.getActiveCount()).isEqualTo(2);
            assertThat(pool.getQueue()).isEmpty();
            assertThat(slots.availablePermits()).isZero();
            for (int i = 0; i < 5; i++) worker.value.tick();
            assertThat(remote.maxActive.get()).isEqualTo(2);
            doThrow(
                            new org.springframework.dao.DataAccessResourceFailureException(
                                    "injected maintenance failure"))
                    .doCallRealMethod()
                    .when(jobs)
                    .recoverExpired();
            assertThatThrownBy(worker.value::maintain)
                    .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
            worker.value.maintain();
            assertThat(store.find(expired).jobState()).isEqualTo("READY");
            assertThat(store.find(expired).nextAction()).isEqualTo("QUERY");
            assertThat(store.find(callback).status()).isEqualTo("FULFILLED");
            assertThat(receipts.find(receipt).applyState()).isEqualTo("APPLIED");
            remote.release.countDown();
            await(() -> slots.availablePermits() == 2, Duration.ofSeconds(5));
            worker.value.tick();
            await(() -> "FULFILLED".equals(store.find(expired).status()), Duration.ofSeconds(5));
            assertThat(store.find(first).status()).isEqualTo("FULFILLED");
            assertThat(store.find(second).status()).isEqualTo("FULFILLED");
            assertThat(remote.maxActive.get()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void rejectedSubmissionAndClaimFailureReturnPermitsAndPersistRecoverableTask()
            throws Exception {
        long id = order();
        try (var remote = new Remote(false);
                var rejected = new ManagedWorker()) {
            doThrow(
                            new org.springframework.dao.DataAccessResourceFailureException(
                                    "injected claim failure"))
                    .doCallRealMethod()
                    .when(jobs)
                    .claim(2);
            assertThatThrownBy(rejected.value::tick)
                    .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
            assertThat(rejected.slots().availablePermits()).isEqualTo(2);
            assertThat(store.find(id).jobState()).isEqualTo("READY");
            rejected.pool().shutdown();
            assertThatThrownBy(rejected.value::tick).isInstanceOf(RejectedExecutionException.class);
            assertThat(rejected.slots().availablePermits()).isEqualTo(2);
            assertThat(store.find(id).jobState()).isEqualTo("RUNNING");
            assertThat(remote.issues.get()).isZero();
            await(() -> !store.now().isBefore(store.find(id).leaseUntil()), Duration.ofSeconds(12));
            try (var restarted = new ManagedWorker()) {
                restarted.value.maintain();
                assertThat(store.find(id).nextAction()).isEqualTo("QUERY");
                restarted.value.tick();
                await(() -> "PAUSED".equals(store.find(id).jobState()), Duration.ofSeconds(4));
                assertThat(store.find(id).status()).isEqualTo("UNKNOWN");
                assertThat(remote.queries.get()).isEqualTo(1);
                assertThat(remote.issues.get()).isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fulfillment_attempts WHERE order_id=?",
                                        Integer.class,
                                        id))
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void shutdownDrainsInflightCallsAndLeavesNewWorkForAnotherWorker() throws Exception {
        long first = order(), second = order();
        try (var remote = new Remote(true);
                var worker = new ManagedWorker()) {
            worker.value.tick();
            assertThat(remote.started.await(3, TimeUnit.SECONDS)).isTrue();
            var stopped = CompletableFuture.runAsync(worker.value::close);
            await(() -> worker.pool().isShutdown(), Duration.ofSeconds(2));
            long waiting = order();
            worker.value.tick();
            assertThat(store.find(waiting).jobState()).isEqualTo("READY");
            remote.release.countDown();
            stopped.get(5, TimeUnit.SECONDS);
            assertThat(store.find(first).status()).isEqualTo("FULFILLED");
            assertThat(store.find(second).status()).isEqualTo("FULFILLED");
            assertThat(worker.slots().availablePermits()).isEqualTo(2);
            try (var successor = new ManagedWorker()) {
                successor.value.tick();
                await(
                        () -> "FULFILLED".equals(store.find(waiting).status()),
                        Duration.ofSeconds(4));
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM fulfillment_attempts WHERE order_id=?",
                                    Integer.class,
                                    waiting))
                    .isEqualTo(1);
        }
    }

    private class ManagedWorker implements AutoCloseable {
        final FulfillmentWorker value;

        ManagedWorker() {
            var configuration = new FulfillmentProperties();
            configuration.setEnabled(true);
            configuration.setConcurrency(2);
            value =
                    new FulfillmentWorker(
                            configuration,
                            jobs,
                            evidence,
                            client,
                            finalizer,
                            store,
                            receipts,
                            (point, identity) -> {});
        }

        ThreadPoolExecutor pool() {
            return (ThreadPoolExecutor) ReflectionTestUtils.getField(value, "executor");
        }

        Semaphore slots() {
            return (Semaphore) ReflectionTestUtils.getField(value, "slots");
        }

        public void close() {
            value.close();
        }
    }

    private class Remote implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        final CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger(),
                maxActive = new AtomicInteger(),
                issues = new AtomicInteger(),
                queries = new AtomicInteger();
        final String previous = properties.getBaseUrl();

        Remote(boolean blocked) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext(
                    "/supplier/fulfillments",
                    exchange -> {
                        int concurrent = active.incrementAndGet();
                        maxActive.accumulateAndGet(concurrent, Math::max);
                        started.countDown();
                        try {
                            SupplierProtocol.IssueRequest request;
                            if ("POST".equals(exchange.getRequestMethod())) {
                                issues.incrementAndGet();
                                request =
                                        json.readValue(
                                                exchange.getRequestBody().readAllBytes(),
                                                SupplierProtocol.IssueRequest.class);
                            } else {
                                queries.incrementAndGet();
                                var order =
                                        store.byRequest(
                                                exchange.getRequestURI()
                                                        .getPath()
                                                        .substring(
                                                                "/supplier/fulfillments/"
                                                                        .length()));
                                request = order.request();
                            }
                            if (blocked) release.await(4, TimeUnit.SECONDS);
                            var result =
                                    new SupplierProtocol.Result(
                                            request.requestNo(),
                                            request.productCode(),
                                            request.subjectRef(),
                                            blocked ? "SUCCEEDED" : "NOT_FOUND",
                                            blocked ? 2L : null,
                                            blocked ? "voucher-" + request.requestNo() : null);
                            byte[] body = json.writeValueAsBytes(result);
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            active.decrementAndGet();
                            exchange.close();
                        }
                    });
            server.start();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        }

        public void close() {
            release.countDown();
            properties.setBaseUrl(previous);
            server.stop(0);
            executor.shutdownNow();
        }
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
                                                                    "容量核验 " + UUID.randomUUID(),
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
        String name = "capacity-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(username,password_hash,role) VALUES(?,'unused','USER')", name);
        long user = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        long id = as(user, Role.USER, () -> Long.parseLong(claims.claim(campaign).orderId()));
        created.add(id);
        jdbc.update("UPDATE fulfillment_orders SET job_state='READY' WHERE id=?", id);
        return id;
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

    private void await(BooleanSupplier condition, Duration budget) throws Exception {
        long deadline = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError("Condition did not converge within " + budget);
            Thread.sleep(25);
        }
    }
}
