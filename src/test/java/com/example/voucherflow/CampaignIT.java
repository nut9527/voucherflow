package com.example.voucherflow;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.common.PageQuery;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.support.MySqlIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证活动发布后的配置冻结、状态转换、分页及 HTTP 权限约束。 */
class CampaignIT extends MySqlIntegrationTest {
    @Autowired private CampaignService campaigns;
    @Autowired private ObjectMapper json;
    private long adminId;

    @BeforeEach
    void loginAdmin() {
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
        authenticateAdmin();
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishesFreezesAndDisablesWithoutDeleting() {
        long id = Long.parseLong(campaigns.create(draft()).id());
        assertThatThrownBy(() -> campaigns.publicDetail(id))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));

        var updated = campaigns.update(id, edit("新的名称", 25));
        assertThat(updated.totalStock()).isEqualTo(25);
        assertThat(updated.availableStock()).isEqualTo(25);
        assertThat(updated.reservedStock() + updated.issuedStock()).isZero();
        var published = campaigns.publish(id);
        assertThat(published.status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(campaigns.publish(id)).isEqualTo(published);
        assertThat(campaigns.publicDetail(id).name()).isEqualTo("新的名称");
        assertThatThrownBy(() -> campaigns.update(id, edit("禁止覆盖", 99)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_STATE));
        assertThat(campaigns.adminDetail(id)).isEqualTo(published);

        var disabled = campaigns.disable(id);
        assertThat(disabled.status()).isEqualTo(CampaignStatus.DISABLED);
        assertThat(campaigns.disable(id)).isEqualTo(disabled);
        assertThat(campaigns.publicDetail(id).name()).isEqualTo("新的名称");
        assertThatThrownBy(() -> campaigns.publish(id)).isInstanceOf(BusinessException.class);
        assertThat(campaigns.adminDetail(id).availableStock()).isEqualTo(25);
    }

    @Test
    void cursorUsesLastReturnedIdWithoutSkippingRows() {
        campaigns.publish(Long.parseLong(campaigns.create(draft()).id()));
        campaigns.publish(Long.parseLong(campaigns.create(draft()).id()));
        var first = campaigns.publicPage(new PageQuery(0, 1));
        var second = campaigns.publicPage(new PageQuery(Long.parseLong(first.nextCursor()), 1));
        List<Long> expected = jdbc.queryForList(
                "SELECT id FROM campaigns WHERE status!='DRAFT' ORDER BY id LIMIT 2", Long.class);
        assertThat(first.nextCursor()).isEqualTo(first.items().getFirst().id());
        assertThat(List.of(Long.parseLong(first.items().getFirst().id()),
                Long.parseLong(second.items().getFirst().id()))).isEqualTo(expected);
    }

    @Test
    void draftEditRacingPublishCannotModifyFrozenConfiguration() throws Exception {
        long id = Long.parseLong(campaigns.create(draft()).id());
        String original = campaigns.adminDetail(id).name();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> {
                authenticateAdmin();
                try {
                    start.await();
                    campaigns.update(id, edit("竞争后的配置", 30));
                    return true;
                } catch (BusinessException ex) {
                    assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAMPAIGN_STATE);
                    return false;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            var publish = executor.submit(() -> {
                authenticateAdmin();
                try {
                    start.await();
                    return campaigns.publish(id);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            start.countDown();
            boolean edited = edit.get(15, TimeUnit.SECONDS);
            var published = publish.get(15, TimeUnit.SECONDS);
            assertThat(published.name()).isEqualTo(edited ? "竞争后的配置" : original);
            assertThat(campaigns.adminDetail(id)).isEqualTo(published);
        }
    }

    @Test
    void httpCreationReturnsStringIdAndUtcTimeAndHidesDraft() throws Exception {
        try (var client = client()) {
            Csrf csrf = login(client, "admin-demo", "test-only-Admin-123");
            var created = exchange(client, "POST", "/api/admin/campaigns", json.writeValueAsString(draft()), csrf);
            assertThat(created.statusCode()).isEqualTo(201);
            String id = JsonPath.parse(created.body()).read("$.data.id", String.class);
            assertThat(id).matches("[1-9][0-9]*");
            var detail = exchange(client, "GET", "/api/admin/campaigns/" + id, null, null);
            String startAt = JsonPath.parse(detail.body()).read("$.data.startAt", String.class);
            assertThat(startAt).endsWith("Z");
            assertThat(exchange(client, "GET", "/api/campaigns/" + id, null, null).statusCode()).isEqualTo(404);
            assertThat(exchange(client, "POST", "/api/admin/campaigns/" + id + "/publish", null, csrf).statusCode()).isEqualTo(200);
            var publicDetail = exchange(client, "GET", "/api/campaigns/" + id, null, null);
            assertThat(publicDetail.statusCode()).isEqualTo(200);
            assertThat(publicDetail.body()).doesNotContain("availableStock", "createdBy", "supplierId");
        }
    }

    @Test
    void httpRejectsUserWritesAndAdminWritesWithoutCsrf() throws Exception {
        try (var client = client()) {
            Csrf userCsrf = login(client, "user-demo", "test-only-User-123");
            var denied = exchange(client, "POST", "/api/admin/campaigns", json.writeValueAsString(draft()), userCsrf);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("FORBIDDEN");
        }
        try (var client = client()) {
            login(client, "admin-demo", "test-only-Admin-123");
            var denied = exchange(client, "POST", "/api/admin/campaigns", json.writeValueAsString(draft()), null);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("CSRF_INVALID");
        }
    }

    @Test
    void httpRejectsInvalidIdsAndPagination() throws Exception {
        try (var client = client()) {
            for (String path : List.of("/api/campaigns/0", "/api/campaigns/-1",
                    "/api/campaigns?size=101", "/api/campaigns?afterId=-1")) {
                assertThat(exchange(client, "GET", path, null, null).statusCode()).as(path).isEqualTo(400);
            }
        }
    }

    private void authenticateAdmin() {
        var principal = new LoginPrincipal(adminId, "admin-demo", Role.ADMIN, null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private CampaignDtos.CreateRequest draft() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new CampaignDtos.CreateRequest("活动-" + UUID.randomUUID(), "活动说明", 10,
                now.minusSeconds(60), now.plusSeconds(3600), now.plusSeconds(7200),
                FulfillmentMode.LOCAL, null, null, null);
    }

    private CampaignDtos.UpdateRequest edit(String name, int stock) {
        var draft = draft();
        return new CampaignDtos.UpdateRequest(name, draft.description(), stock, draft.startAt(),
                draft.endAt(), draft.redeemEndAt(), draft.fulfillmentMode(), null, null, null);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private Csrf login(HttpClient client, String username, String password) throws Exception {
        Csrf csrf = csrf(client);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .timeout(Duration.ofSeconds(10)).header(csrf.header(), csrf.token())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        return csrf(client);
    }

    private Csrf csrf(HttpClient client) throws Exception {
        var response = exchange(client, "GET", "/api/auth/csrf", null, null);
        var body = JsonPath.parse(response.body());
        return new Csrf(body.read("$.data.headerName", String.class), body.read("$.data.token", String.class));
    }

    private HttpResponse<String> exchange(HttpClient client, String method, String path, String body, Csrf csrf)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) {
            request.header(csrf.header(), csrf.token());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Csrf(String header, String token) {}
}
