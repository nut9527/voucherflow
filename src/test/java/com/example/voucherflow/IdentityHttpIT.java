package com.example.voucherflow;

import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.support.MySqlIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过真实 HTTP 会话验证登录、CSRF、角色授权及会话凭证清理。 */
class IdentityHttpIT extends MySqlIntegrationTest {
    private CookieManager cookies;
    private HttpClient client;

    @BeforeEach
    void createClient() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void anonymousRequestReturnsJsonAndTraceId() throws Exception {
        var response = get("/api/auth/me");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(value(response, "$.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(value(response, "$.traceId"));
    }

    @Test
    void loginRequiresCsrfAndRotatesTheSession() throws Exception {
        var missing = post("/api/auth/login", "username=user-demo&password=test-only-User-123", null);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(value(missing, "$.code")).isEqualTo("CSRF_INVALID");

        var before = csrf();
        String previousSession = sessionId();
        var login = login("USER-DEMO", "test-only-User-123", before);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(value(login, "$.data.username")).isEqualTo("user-demo");
        assertThat(value(login, "$.data.role")).isEqualTo("USER");
        assertThat(sessionId()).isNotEqualTo(previousSession);
        assertThat(get("/api/auth/me").statusCode()).isEqualTo(200);

        var staleToken = post("/api/auth/logout", "", before);
        assertThat(staleToken.statusCode()).isEqualTo(403);
        assertThat(value(staleToken, "$.code")).isEqualTo("CSRF_INVALID");
    }

    @Test
    void unknownUserAndWrongPasswordReturnTheSameFailure() throws Exception {
        var csrf = csrf();
        var missing = login("missing-account", "wrong-password", csrf);
        var wrongPassword = login("user-demo", "wrong-password", csrf);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(value(missing, "$.code")).isEqualTo("BAD_CREDENTIALS");
        assertThat(value(wrongPassword, "$.code")).isEqualTo("BAD_CREDENTIALS");
        assertThat(value(missing, "$.message")).isEqualTo(value(wrongPassword, "$.message"));
    }

    @Test
    void roleDenialIsSeparateFromCsrfDenial() throws Exception {
        assertThat(login("user-demo", "test-only-User-123", csrf()).statusCode()).isEqualTo(200);
        var response = post("/api/vouchers/1/redeem", "", csrf());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(value(response, "$.code")).isEqualTo("FORBIDDEN");
        assertThat(get("/api/admin/campaigns").statusCode()).isEqualTo(403);
    }

    @Test
    void adminCannotClaimUserBenefits() throws Exception {
        assertThat(login("admin-demo", "test-only-Admin-123", csrf()).statusCode()).isEqualTo(200);
        var response = post("/api/campaigns/1/claim", "", csrf());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(value(response, "$.code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void jdbcSessionContainsOnlySanitizedPrincipalAndLogoutInvalidatesIt() throws Exception {
        assertThat(login("user-demo", "test-only-User-123", csrf()).statusCode()).isEqualTo(200);
        String sessionId = sessionId();
        byte[] attributes = jdbc.queryForObject("""
                SELECT a.ATTRIBUTE_BYTES
                FROM SPRING_SESSION_ATTRIBUTES a
                JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
                WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'
                """, byte[].class, sessionId);
        String storedHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'user-demo'", String.class);
        assertThat(new String(attributes, StandardCharsets.ISO_8859_1))
                .doesNotContain(storedHash).doesNotContain("test-only-User-123");
        try (var input = new ObjectInputStream(new ByteArrayInputStream(attributes))) {
            var context = (SecurityContext) input.readObject();
            assertThat(context.getAuthentication().getCredentials()).isNull();
            assertThat(((LoginPrincipal) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        }

        assertThat(post("/api/auth/logout", "", csrf()).statusCode()).isEqualTo(200);
        assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?", Integer.class, sessionId))
                .isZero();
    }

    private Csrf csrf() throws Exception {
        var response = get("/api/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        return new Csrf(value(response, "$.data.headerName"), value(response, "$.data.token"));
    }

    private HttpResponse<String> login(String username, String password, Csrf csrf) throws Exception {
        return post("/api/auth/login", "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), csrf);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, Csrf csrf) throws Exception {
        var request = request(path).header("Content-Type", "application/x-www-form-urlencoded");
        if (csrf != null) {
            request.header(csrf.headerName(), csrf.token());
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private String value(HttpResponse<String> response, String path) {
        return JsonPath.parse(response.body()).read(path, String.class);
    }

    private String sessionId() {
        String cookie = cookies.getCookieStore().getCookies().stream()
                .filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }

    private record Csrf(String headerName, String token) { }
}
