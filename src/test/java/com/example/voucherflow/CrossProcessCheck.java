package com.example.voucherflow;

import com.example.voucherflow.support.IsolatedRocketMq;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 启动两个打包应用进程，验证会话共享、数据库约束与消息消费组协作。 */
class CrossProcessCheck {
    private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final String PASSWORD = "cross-process-test-only-123";

    @Test @Timeout(210)
    void twoPidsShareSessionsAndNeverDuplicateOrdersStockOrRedemption() throws Exception {
        Path evidence = PROJECT.resolve("target/cross-process-check/" + UUID.randomUUID()); Files.createDirectories(evidence);
        Path jar = Files.copy(PROJECT.resolve("target/voucherflow.jar"), evidence.resolve("application.jar"));
        try (var db = new MySQLContainer("mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902")
                .withDatabaseName("cross_process").withUsername("cross_test").withPassword("cross-only-password")
                .withCommand("--default-time-zone=+00:00"); var broker = new IsolatedRocketMq()) {
            db.start(); broker.start();
            try (var first = launch(jar, db, broker.endpoint(), evidence.resolve("first.log"));
                    var second = launch(jar, db, broker.endpoint(), evidence.resolve("second.log"))) {
                first.awaitReady(); second.awaitReady(); assertThat(first.process.pid()).isNotEqualTo(second.process.pid());
                var admin = new Api(first.base(), "admin-demo"); var user = new Api(first.base(), "user-demo");
                assertThat(user.get(second.base(), "/api/auth/me").path("username").asString()).isEqualTo("user-demo");
                long sameCampaign = campaign(admin, first.base(), 1);
                var same = compete(8, index -> user.request(index % 2 == 0 ? first.base() : second.base(),
                        "POST", "/api/campaigns/" + sameCampaign + "/claim", null));
                assertThat(same).allSatisfy(response -> assertThat(response.status).isIn(200, 201));
                var created = same.stream().filter(response -> response.status == 201).toList();
                assertThat(created).hasSize(1);
                String orderId = created.getFirst().data().path("orderId").asString();
                String voucherId = created.getFirst().data().path("voucherId").asString();
                assertThat(same).allSatisfy(response -> assertThat(response.data().path("orderId").asString()).isEqualTo(orderId));
                assertThat(number(db, "SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=" + sameCampaign)).isEqualTo(1);

                var redeemed = compete(8, index -> admin.request(index % 2 == 0 ? first.base() : second.base(),
                        "POST", "/api/vouchers/" + voucherId + "/redeem", null));
                assertThat(redeemed).allSatisfy(response -> assertThat(response.status).isIn(200, 201));
                assertThat(redeemed.stream().filter(response -> !response.data().path("replayed").asBoolean())).hasSize(1);
                String receiptId = redeemed.getFirst().data().path("id").asString();
                assertThat(redeemed).allSatisfy(response -> assertThat(response.data().path("id").asString()).isEqualTo(receiptId));
                assertThat(number(db, "SELECT COUNT(*) FROM redemption_records WHERE voucher_id=" + voucherId)).isEqualTo(1);

                long stockCampaign = campaign(admin, first.base(), 7);
                List<Api> users = new ArrayList<>();
                String hash = new BCryptPasswordEncoder().encode(PASSWORD);
                try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                        var insert = connection.prepareStatement("INSERT INTO users(username,password_hash,role) VALUES(?,?,'USER')")) {
                    for (int index = 0; index < 24; index++) {
                        String username = "cross-" + index; insert.setString(1, username); insert.setString(2, hash); insert.executeUpdate();
                        users.add(new Api(index % 2 == 0 ? first.base() : second.base(), username));
                    }
                }
                var competition = compete(users.size(), index -> users.get(index).request(index % 2 == 0 ? second.base() : first.base(),
                        "POST", "/api/campaigns/" + stockCampaign + "/claim", null));
                assertThat(competition.stream().filter(response -> response.status == 201)).hasSize(7);
                assertThat(competition.stream().filter(response -> response.status != 201)).allSatisfy(response -> {
                    assertThat(response.status).isEqualTo(409); assertThat(response.body.path("code").asString()).isEqualTo("OUT_OF_STOCK");
                });
                assertThat(number(db, "SELECT COUNT(*) FROM fulfillment_orders WHERE campaign_id=" + stockCampaign)).isEqualTo(7);
                assertThat(number(db, "SELECT COUNT(*) FROM user_vouchers WHERE campaign_id=" + stockCampaign)).isEqualTo(7);
                assertThat(number(db, "SELECT COUNT(*) FROM stock_ledger WHERE campaign_id=" + stockCampaign + " AND entry_type='CONFIRM'")).isEqualTo(7);
                assertThat(number(db, "SELECT COUNT(*) FROM campaigns WHERE id=" + stockCampaign + " AND available_stock=0 AND reserved_stock=0 AND issued_stock=7")).isEqualTo(1);
                await(() -> number(db, "SELECT COUNT(*) FROM notifications") == 8, Duration.ofSeconds(35));
                assertThat(number(db, "SELECT COUNT(*) FROM consumed_events WHERE consumer_name='vf_notification_worker'")).isEqualTo(8);
                assertThat(admin.get(second.base(), "/api/admin/campaigns/" + stockCampaign + "/stats").path("consistent").asBoolean()).isTrue();
                Files.writeString(evidence.resolve("result.json"), JSON.writeValueAsString(Map.of(
                        "firstPid", first.process.pid(), "secondPid", second.process.pid(), "sharedSession", true,
                        "sameOrderRequests", 8, "oneRedemption", true, "competingUsers", 24, "stock", 7, "issued", 7,
                        "notifications", 8)));
            }
        }
    }
    private long campaign(Api admin, String base, int stock) throws Exception {
        var now = Instant.now();
        var response = admin.request(base, "POST", "/api/admin/campaigns", Map.of("name", "跨进程库存验证", "description", "",
                "totalStock", stock, "startAt", now.minusSeconds(10), "endAt", now.plusSeconds(3600),
                "redeemEndAt", now.plusSeconds(7200), "fulfillmentMode", "LOCAL"));
        assertThat(response.status).isEqualTo(201); long id = response.data().path("id").asLong();
        assertThat(admin.request(base, "POST", "/api/admin/campaigns/" + id + "/publish", null).status).isEqualTo(200);
        return id;
    }
    private List<Response> compete(int count, CheckedRequest request) throws Exception {
        var ready = new CountDownLatch(count); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<Response>>();
            for (int index = 0; index < count; index++) {
                int selected = index; futures.add(pool.submit(() -> { ready.countDown(); release.await(); return request.call(selected); }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
            var result = new ArrayList<Response>(); for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        } finally { release.countDown(); }
    }
    @FunctionalInterface private interface CheckedRequest { Response call(int index) throws Exception; }
    private Managed launch(Path jar, MySQLContainer db, String mq, Path log) throws Exception {
        int port = freePort();
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + PROJECT.resolve(".local/java-user"), "-Djava.io.tmpdir=" + PROJECT.resolve(".local/tmp"),
                "-jar", jar.toString(), "--server.port=" + port, "--spring.profiles.active=local",
                "--spring.datasource.url=" + db.getJdbcUrl() + (db.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "--spring.datasource.username=" + db.getUsername(), "--spring.datasource.password=" + db.getPassword(),
                "--voucherflow.demo-users.enabled=true", "--voucherflow.demo-users.admin-password=" + PASSWORD,
                "--voucherflow.demo-users.user-password=" + PASSWORD, "--voucherflow.cache.enabled=false",
                "--voucherflow.rate-limit.enabled=false", "--voucherflow.fulfillment.enabled=false",
                "--voucherflow.messaging.enabled=true", "--voucherflow.messaging.publishing-enabled=true",
                "--voucherflow.messaging.endpoints=" + mq, "--voucherflow.messaging.consumer-threads=1", "--voucherflow.messaging.send-threads=1");
        var builder = new ProcessBuilder(command).directory(PROJECT.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("VF_"));
        return new Managed(builder.start(), port, log);
    }
    private int freePort() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            int candidate = ThreadLocalRandom.current().nextInt(20000, 40000);
            try (var socket = new ServerSocket(candidate)) { return socket.getLocalPort(); }
            catch (java.net.BindException occupied) { }
        }
        throw new IllegalStateException("No free test server port");
    }
    private static long number(MySQLContainer db, String sql) {
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue(); return rows.getLong(1);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static void await(BooleanSupplier condition, Duration budget) throws Exception {
        long deadline = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Cross-process check did not converge");
            Thread.sleep(100);
        }
    }
    private record Managed(Process process, int port, Path log) implements AutoCloseable {
        String base() { return "http://127.0.0.1:" + port; }
        void awaitReady() throws Exception {
            await(() -> {
                if (!process.isAlive()) throw new AssertionError("Application exited; inspect " + log);
                try { return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
                catch (Exception unavailable) { return false; }
            }, Duration.ofSeconds(60));
        }
        @Override public void close() {
            if (process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); } }
        }
    }
    private record Response(int status, JsonNode body) { JsonNode data() { return body.path("data"); } }
    private static class Api {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String header, token;
        Api(String base, String username) throws Exception {
            csrf(base);
            var login = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header(header, token)
                    .header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD)).build();
            assertThat(client.send(login, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200); csrf(base);
        }
        void csrf(String base) throws Exception {
            var result = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            var data = JSON.readTree(result.body()).path("data"); header = data.path("headerName").asString(); token = data.path("token").asString();
        }
        JsonNode get(String base, String path) throws Exception {
            var response = request(base, "GET", path, null); assertThat(response.status).isEqualTo(200); return response.data();
        }
        Response request(String base, String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).header(header, token).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), JSON.readTree(response.body()));
        }
    }
}
