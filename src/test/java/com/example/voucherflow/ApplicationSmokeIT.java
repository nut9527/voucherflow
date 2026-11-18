package com.example.voucherflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.voucherflow.support.MySqlIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证应用基础 HTTP 契约，包括 CSRF、追踪编号、健康探针与错误响应。 */
class ApplicationSmokeIT extends MySqlIntegrationTest {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterAll
    static void closeClient() {
        HTTP.close();
    }

    @Test
    void csrfReturnsTheResponseContractAndAServerGeneratedTraceId() throws Exception {
        var response = get("/api/auth/csrf");
        var json = JsonPath.parse(response.body());
        String traceId = json.read("$.traceId");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/json");
        assertThat(json.<Map<String, Object>>read("$"))
                .containsOnlyKeys("code", "message", "data", "traceId");
        assertThat(json.<String>read("$.code")).isEqualTo("OK");
        assertThat(json.<String>read("$.message")).isEqualTo("成功");
        assertThat(json.<String>read("$.data.token")).isNotBlank();
        assertThat(response.headers().allValues("X-Trace-Id")).containsExactly(traceId);
        assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
        assertThat(traceId).isNotEqualTo("client-supplied-trace");
    }

    @Test
    void separateRequestsGetDifferentTraceIds() throws Exception {
        String first = get("/api/auth/csrf").headers().firstValue("X-Trace-Id").orElseThrow();
        String second = get("/api/auth/csrf").headers().firstValue("X-Trace-Id").orElseThrow();

        assertThat(first).isNotEqualTo(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"liveness", "readiness"})
    void healthProbesAreUpWithoutExposingDetails(String probe) throws Exception {
        var response = get("/actuator/health/" + probe);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(response.body()).<Map<String, Object>>read("$"))
                .containsExactlyEntriesOf(Map.of("status", "UP"));
    }

    @Test
    void diagnosticEnvironmentEndpointIsNotExposed() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
    }

    @Test
    void unsupportedHttpMethodReturnsMethodNotAllowed() throws Exception {
        try (var client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()) {
            var csrfResponse =
                    client.send(
                            request("/api/auth/csrf").GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(csrfResponse.statusCode()).isEqualTo(200);
            var csrf = JsonPath.parse(csrfResponse.body());
            var login =
                    request("/api/auth/login")
                            .header(
                                    csrf.read("$.data.headerName", String.class),
                                    csrf.read("$.data.token", String.class))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "username=admin-demo&password=test-only-Admin-123"))
                            .build();
            assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);

            var response =
                    client.send(
                            request("/api/admin/campaigns/1/publish").GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(JsonPath.parse(response.body()).<String>read("$.code"))
                    .isEqualTo("METHOD_NOT_ALLOWED");
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Trace-Id", "client-supplied-trace");
    }
}
