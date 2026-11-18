package com.example.voucherflow;

import static org.assertj.core.api.Assertions.*;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 使用打包应用、供应商模拟器及隔离依赖容器，验证进程故障后的业务恢复。 */
class ProcessRecoveryCheck {
    private String mqEndpoint;
    private static final String SECRET = "process-check-only-secret-at-least-24";
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
    private static final String IMAGE =
            "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902";

    @Test
    @Timeout(240)
    void acknowledgedTaskAndRemoteCommitRecoverAcrossDifferentPids() throws Exception {
        String runId = UUID.randomUUID().toString();
        Path evidence = PROJECT.resolve("target/process-check/" + runId);
        Files.createDirectories(evidence);
        Path appJar =
                Files.copy(
                        PROJECT.resolve("target/voucherflow.jar"),
                        evidence.resolve("application.jar"));
        Path supplierJar =
                Files.copy(
                        PROJECT.resolve("supplier-simulator/target/supplier-simulator.jar"),
                        evidence.resolve("supplier.jar"));
        List<ManagedProcess> owned = new ArrayList<>();
        List<Path> probes = new ArrayList<>();
        try (var database = mysql("process_main");
                var supplierDatabase = mysql("process_supplier");
                var broker = new IsolatedRocketMq()) {
            database.start();
            supplierDatabase.start();
            broker.start();
            mqEndpoint = broker.endpoint();
            int appPort = freePort(), supplierPort = freePort();
            String base = "http://127.0.0.1:" + appPort;
            var supplier =
                    launch(
                            supplierJar,
                            supplierPort,
                            supplierDatabase,
                            evidence.resolve("supplier.log"),
                            List.of(
                                    "--supplier.api-token=" + SECRET,
                                    "--supplier.callback-secret=" + SECRET,
                                    "--supplier.faults-enabled=true",
                                    "--supplier.callback-url=" + base + "/api/supplier/callbacks"));
            owned.add(supplier);
            supplier.awaitReady("/actuator/health");
            var pause =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + supplierPort
                                                    + "/supplier/faults"))
                            .header("Authorization", "Bearer " + SECRET)
                            .header("Content-Type", "application/json")
                            .PUT(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"dropNextResponse\":false,\"pauseCallbacks\":true}"))
                            .timeout(Duration.ofSeconds(5))
                            .build();
            assertThat(
                            HttpClient.newHttpClient()
                                    .send(pause, HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .isEqualTo(200);

            String ackRun = runId + "-ack";
            Path ackProbe = PROJECT.resolve(".local/faults/" + ackRun);
            assertThat(Files.exists(ackProbe)).isFalse();
            probes.add(ackProbe);
            var first =
                    app(
                            appJar,
                            appPort,
                            supplierPort,
                            database,
                            evidence.resolve("after-ack.log"),
                            false,
                            true,
                            "AFTER_FULFILLMENT_ACK",
                            ackRun);
            owned.add(first);
            first.awaitReady("/actuator/health/readiness");
            var admin = new Api(base, "admin-demo", "test-only-Admin-123");
            var user = new Api(base, "user-demo", "test-only-User-123");
            long campaign = campaign(admin);
            long order =
                    user.call("POST", "/api/campaigns/" + campaign + "/claim", null, 202)
                            .path("orderId")
                            .asLong();
            await(() -> Files.exists(ackProbe.resolve("reached")));
            assertThat(text(database, "SELECT job_state FROM fulfillment_orders WHERE id=" + order))
                    .isEqualTo("READY");
            String eventId =
                    text(
                            database,
                            "SELECT id FROM outbox_events WHERE event_type='FulfillmentRequested.v1' AND aggregate_id='"
                                    + order
                                    + "'");
            assertThat(Files.readString(ackProbe.resolve("reached")))
                    .contains("AFTER_FULFILLMENT_ACK " + eventId + " " + first.pid());
            assertThat(
                            number(
                                    database,
                                    "SELECT COUNT(*) FROM consumed_events WHERE event_id='"
                                            + eventId
                                            + "'"))
                    .isEqualTo(1);
            assertThat(number(supplierDatabase, "SELECT COUNT(*) FROM supplier_requests")).isZero();
            first.crash();
            var second =
                    app(
                            appJar,
                            appPort,
                            supplierPort,
                            database,
                            evidence.resolve("recover-ack.log"),
                            true,
                            false,
                            "",
                            "");
            owned.add(second);
            second.awaitReady("/actuator/health/readiness");
            assertThat(second.pid()).isNotEqualTo(first.pid());
            await(
                    () ->
                            text(
                                            database,
                                            "SELECT status FROM fulfillment_orders WHERE id="
                                                    + order)
                                    .equals("FULFILLED"));
            assertConservation(database, supplierDatabase, order, campaign);
            second.crash();

            String remoteRun = runId + "-remote";
            Path remoteProbe = PROJECT.resolve(".local/faults/" + remoteRun);
            assertThat(Files.exists(remoteProbe)).isFalse();
            probes.add(remoteProbe);
            var third =
                    app(
                            appJar,
                            appPort,
                            supplierPort,
                            database,
                            evidence.resolve("after-remote.log"),
                            true,
                            true,
                            "AFTER_SUPPLIER_RESULT",
                            remoteRun);
            owned.add(third);
            third.awaitReady("/actuator/health/readiness");
            // 数据库持久化的会话在新应用进程中仍然有效。
            long remoteCampaign = campaign(admin);
            long remoteOrder =
                    user.call("POST", "/api/campaigns/" + remoteCampaign + "/claim", null, 202)
                            .path("orderId")
                            .asLong();
            await(() -> Files.exists(remoteProbe.resolve("reached")));
            assertThat(Files.readString(remoteProbe.resolve("reached")))
                    .contains("AFTER_SUPPLIER_RESULT " + remoteOrder + " " + third.pid());
            String requestNo =
                    text(
                            database,
                            "SELECT request_no FROM fulfillment_orders WHERE id=" + remoteOrder);
            assertThat(
                            text(
                                    database,
                                    "SELECT status FROM fulfillment_orders WHERE id="
                                            + remoteOrder))
                    .isEqualTo("UNKNOWN");
            assertThat(
                            number(
                                    database,
                                    "SELECT reserved_stock FROM campaigns WHERE id="
                                            + remoteCampaign))
                    .isEqualTo(1);
            assertThat(
                            number(
                                    database,
                                    "SELECT COUNT(*) FROM supplier_receipts WHERE order_id="
                                            + remoteOrder))
                    .isZero();
            assertThat(
                            number(
                                    supplierDatabase,
                                    "SELECT COUNT(*) FROM supplier_vouchers WHERE request_no='"
                                            + requestNo
                                            + "'"))
                    .isEqualTo(1);
            third.crash();
            var fourth =
                    app(
                            appJar,
                            appPort,
                            supplierPort,
                            database,
                            evidence.resolve("recover-remote.log"),
                            true,
                            false,
                            "",
                            "");
            owned.add(fourth);
            fourth.awaitReady("/actuator/health/readiness");
            assertThat(fourth.pid()).isNotEqualTo(third.pid());
            await(
                    () ->
                            text(
                                            database,
                                            "SELECT status FROM fulfillment_orders WHERE id="
                                                    + remoteOrder)
                                    .equals("FULFILLED"));
            assertThat(
                            text(
                                    database,
                                    "SELECT request_no FROM fulfillment_orders WHERE id="
                                            + remoteOrder))
                    .isEqualTo(requestNo);
            assertThat(
                            number(
                                    database,
                                    "SELECT issue_attempts FROM fulfillment_orders WHERE id="
                                            + remoteOrder))
                    .isEqualTo(1);
            assertThat(
                            number(
                                    database,
                                    "SELECT query_attempts FROM fulfillment_orders WHERE id="
                                            + remoteOrder))
                    .isGreaterThanOrEqualTo(1);
            assertConservation(database, supplierDatabase, remoteOrder, remoteCampaign);
            Files.writeString(
                    evidence.resolve("result.json"),
                    JSON.writeValueAsString(
                            Map.of(
                                    "ackRecovery",
                                    true,
                                    "remoteCommitRecovery",
                                    true,
                                    "firstPid",
                                    first.pid(),
                                    "secondPid",
                                    second.pid(),
                                    "thirdPid",
                                    third.pid(),
                                    "fourthPid",
                                    fourth.pid(),
                                    "ackOrderId",
                                    order,
                                    "remoteOrderId",
                                    remoteOrder)));
        } finally {
            for (var process : owned.reversed()) {
                process.close();
            }
            for (var probe : probes) {
                if (Files.isDirectory(probe)) {
                    try (var paths = Files.walk(probe)) {
                        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(path);
                        }
                    }
                }
            }
        }
    }

    private long campaign(Api admin) throws Exception {
        var now = Instant.now();
        long campaign =
                admin.call(
                                "POST",
                                "/api/admin/campaigns",
                                Map.of(
                                        "name",
                                        "进程恢复验证",
                                        "description",
                                        "",
                                        "totalStock",
                                        1,
                                        "startAt",
                                        now.minusSeconds(10),
                                        "endAt",
                                        now.plusSeconds(3600),
                                        "redeemEndAt",
                                        now.plusSeconds(7200),
                                        "fulfillmentMode",
                                        "SUPPLIER",
                                        "supplierId",
                                        "simulator",
                                        "productCode",
                                        "COURSE",
                                        "contractVersion",
                                        "v1"),
                                201)
                        .path("id")
                        .asLong();
        admin.call("POST", "/api/admin/campaigns/" + campaign + "/publish", null, 200);
        return campaign;
    }

    private void assertConservation(
            MySQLContainer local, MySQLContainer supplier, long order, long campaign) {
        String request = text(local, "SELECT request_no FROM fulfillment_orders WHERE id=" + order);
        assertThat(number(local, "SELECT COUNT(*) FROM user_vouchers WHERE order_id=" + order))
                .isEqualTo(1);
        assertThat(
                        number(
                                local,
                                "SELECT COUNT(*) FROM stock_ledger WHERE entry_type='CONFIRM' AND order_id="
                                        + order))
                .isEqualTo(1);
        assertThat(
                        number(
                                local,
                                "SELECT COUNT(*) FROM stock_ledger WHERE entry_type='RELEASE' AND order_id="
                                        + order))
                .isZero();
        assertThat(number(local, "SELECT reserved_stock FROM campaigns WHERE id=" + campaign))
                .isZero();
        assertThat(number(local, "SELECT issued_stock FROM campaigns WHERE id=" + campaign))
                .isEqualTo(1);
        assertThat(
                        number(
                                supplier,
                                "SELECT COUNT(*) FROM supplier_vouchers WHERE request_no='"
                                        + request
                                        + "'"))
                .isEqualTo(1);
    }

    private MySQLContainer mysql(String name) {
        return new MySQLContainer(DockerImageName.parse(IMAGE))
                .withDatabaseName(name)
                .withUsername("process_test")
                .withPassword("process-only-password")
                .withCommand("--default-time-zone=+00:00");
    }

    private ManagedProcess app(
            Path jar,
            int port,
            int supplierPort,
            MySQLContainer db,
            Path log,
            boolean worker,
            boolean messaging,
            String point,
            String runId)
            throws Exception {
        return launch(
                jar,
                port,
                db,
                log,
                List.of(
                        "--spring.profiles.active=local,demo",
                        "--voucherflow.demo-users.enabled=true",
                        "--voucherflow.demo-users.admin-password=test-only-Admin-123",
                        "--voucherflow.demo-users.user-password=test-only-User-123",
                        "--voucherflow.fulfillment.enabled=true",
                        "--voucherflow.fulfillment.worker-enabled=" + worker,
                        "--voucherflow.fulfillment.base-url=http://127.0.0.1:" + supplierPort,
                        "--voucherflow.fulfillment.api-token=" + SECRET,
                        "--voucherflow.fulfillment.callback-secret=" + SECRET,
                        "--voucherflow.fulfillment.lease-seconds=10",
                        "--voucherflow.messaging.enabled=" + messaging,
                        "--voucherflow.messaging.publishing-enabled=true",
                        "--voucherflow.messaging.endpoints=" + mqEndpoint,
                        "--voucherflow.cache.enabled=false",
                        "--voucherflow.rate-limit.enabled=false",
                        "--voucherflow.demo.probe-point=" + point,
                        "--voucherflow.demo.run-id=" + runId));
    }

    private ManagedProcess launch(
            Path jar, int port, MySQLContainer db, Path log, List<String> args) throws Exception {
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-Duser.home=" + PROJECT.resolve(".local/java-user"),
                                "-Djava.io.tmpdir=" + PROJECT.resolve(".local/tmp"),
                                "-jar",
                                jar.toString(),
                                "--server.port=" + port,
                                "--spring.datasource.url="
                                        + db.getJdbcUrl()
                                        + (db.getJdbcUrl().contains("?") ? "&" : "?")
                                        + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                                "--spring.datasource.username=" + db.getUsername(),
                                "--spring.datasource.password=" + db.getPassword()));
        command.addAll(args);
        var builder =
                new ProcessBuilder(command)
                        .directory(PROJECT.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("VF_"));
        return new ManagedProcess(builder.start(), port, log);
    }

    private static int freePort() throws Exception {
        // 服务端端口避开 Windows 客户端临时端口范围，便于强制重启后继续绑定。
        for (int attempt = 0; attempt < 30; attempt++) {
            int candidate = java.util.concurrent.ThreadLocalRandom.current().nextInt(20000, 40000);
            try (var socket = new ServerSocket(candidate)) {
                return socket.getLocalPort();
            } catch (java.net.BindException occupied) {
            }
        }
        throw new IllegalStateException("No free process-test server port");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) {
                throw new AssertionError("Process experiment did not converge");
            }
            Thread.sleep(100);
        }
    }

    private static String text(MySQLContainer db, String sql) {
        try (var connection =
                        DriverManager.getConnection(
                                db.getJdbcUrl(), db.getUsername(), db.getPassword());
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new AssertionError("Missing process evidence");
            }
            return rows.getString(1);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long number(MySQLContainer db, String sql) {
        return Long.parseLong(text(db, sql));
    }

    private record ManagedProcess(Process process, int port, Path log) implements AutoCloseable {
        long pid() {
            return process.pid();
        }

        void awaitReady(String health) throws Exception {
            await(
                    () -> {
                        if (!process.isAlive()) {
                            throw new AssertionError("Child exited; inspect " + log);
                        }
                        try {
                            return HttpClient.newHttpClient()
                                            .send(
                                                    HttpRequest.newBuilder(
                                                                    URI.create(
                                                                            "http://127.0.0.1:"
                                                                                    + port
                                                                                    + health))
                                                            .timeout(Duration.ofSeconds(2))
                                                            .GET()
                                                            .build(),
                                                    HttpResponse.BodyHandlers.discarding())
                                            .statusCode()
                                    == 200;
                        } catch (Exception ex) {
                            return false;
                        }
                    });
        }

        void crash() throws Exception {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static class Api {
        final HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        final String base;
        String csrfHeader, csrfToken;

        Api(String base, String user, String password) throws Exception {
            this.base = base;
            csrf();
            var login =
                    HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                            .header(csrfHeader, csrfToken)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "username=" + user + "&password=" + password))
                            .timeout(Duration.ofSeconds(10))
                            .build();
            assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
            csrf();
        }

        void csrf() throws Exception {
            var response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf"))
                                    .GET()
                                    .timeout(Duration.ofSeconds(10))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            var data = JSON.readTree(response.body()).path("data");
            csrfHeader = data.path("headerName").asString();
            csrfToken = data.path("token").asString();
        }

        JsonNode call(String method, String path, Object body, int status) throws Exception {
            var request =
                    HttpRequest.newBuilder(URI.create(base + path))
                            .header(csrfHeader, csrfToken)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .method(
                                    method,
                                    body == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofString(
                                                    JSON.writeValueAsString(body)))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .withFailMessage("%s %s: %s", method, path, response.body())
                    .isEqualTo(status);
            return JSON.readTree(response.body()).path("data");
        }
    }
}
