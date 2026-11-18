package com.example.vfsupplier;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 使用真实 HTTP 与 MySQL 验证幂等发放、响应丢失恢复和协议状态语义。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "supplier.api-token=test-only-supplier-api-token",
            "supplier.callback-secret=test-only-supplier-callback-secret",
            "supplier.faults-enabled=true"
        })
class SupplierContractIT {
    @Container static final MySQLContainer database = new MySQLContainer("mysql:8.4.8");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DemoFaults faults;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    // 暂停外发回调，使断言聚焦于发放接口及已持久化的回调任务。
    @BeforeEach
    void pauseCallbacks() {
        faults.set(false, true);
    }

    @Test
    void sameRequestSameParametersAndQueryReturnOnePersistedVoucher() throws Exception {
        var request =
                new SupplierDtos.IssueRequest(UUID.randomUUID().toString(), "course", "subject");
        var first = issue(request);
        var second = issue(request);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(query(request.requestNo()).body()).isEqualTo(first.body());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM supplier_vouchers WHERE request_no=?",
                                Long.class,
                                request.requestNo()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM callback_jobs WHERE request_no=?",
                                Long.class,
                                request.requestNo()))
                .isEqualTo(2);
        assertThat(
                        issue(
                                        new SupplierDtos.IssueRequest(
                                                request.requestNo(), "other", "subject"))
                                .statusCode())
                .isEqualTo(409);
    }

    // HTTP 返回失败并不代表发放失败，查单和重试仍应得到同一张券。
    @Test
    void commitThenLostResponseIsRecoverableByHttpQuery() throws Exception {
        faults.set(true, true);
        var request =
                new SupplierDtos.IssueRequest(UUID.randomUUID().toString(), "course", "subject");
        assertThat(issue(request).statusCode()).isEqualTo(503);
        var recovered =
                json.readValue(query(request.requestNo()).body(), SupplierDtos.Result.class);
        assertThat(recovered.status()).isEqualTo("SUCCEEDED");
        assertThat(recovered.voucherRef()).isNotBlank();
        assertThat(issue(request).statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM supplier_vouchers WHERE request_no=?",
                                Long.class,
                                request.requestNo()))
                .isEqualTo(1);
    }

    // 多线程提交同一请求，验证唯一约束与行锁共同保证只发放一次。
    @Test
    void concurrentDuplicateHasOneEffect() throws Exception {
        var request =
                new SupplierDtos.IssueRequest(UUID.randomUUID().toString(), "course", "subject");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 16; i++)
                futures.add(executor.submit(() -> issue(request).statusCode()));
            for (var result : futures)
                assertThat(result.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM supplier_vouchers WHERE request_no=?",
                                Long.class,
                                request.requestNo()))
                .isEqualTo(1);
    }

    @Test
    void rejectionNeverCreatesVoucherAndMissingIsNotRejected() throws Exception {
        var request =
                new SupplierDtos.IssueRequest(UUID.randomUUID().toString(), "reject", "subject");
        assertThat(json.readValue(issue(request).body(), SupplierDtos.Result.class).status())
                .isEqualTo("REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM supplier_vouchers WHERE request_no=?",
                                Long.class,
                                request.requestNo()))
                .isZero();
        assertThat(
                        json.readValue(
                                        query(UUID.randomUUID().toString()).body(),
                                        SupplierDtos.Result.class)
                                .status())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void authenticationRequired() throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(URI.create(url() + "/supplier/fulfillments/missing"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> issue(SupplierDtos.IssueRequest request) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url() + "/supplier/fulfillments"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer test-only-supplier-api-token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> query(String id) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url() + "/supplier/fulfillments/" + id))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer test-only-supplier-api-token")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
