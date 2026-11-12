package com.example.voucherflow;

import com.example.voucherflow.campaign.CampaignDtos;
import com.example.voucherflow.campaign.CampaignService;
import com.example.voucherflow.campaign.cache.CampaignCacheInvalidator;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.identity.LoginPrincipal;
import com.example.voucherflow.identity.model.Role;
import com.example.voucherflow.support.MySqlIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

/** 结合真实 MySQL 与 Redis 验证详情缓存、空值缓存及事务提交后的失效行为。 */
@Testcontainers
class CampaignCacheIT extends MySqlIntegrationTest {
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        registry.add("voucherflow.cache.enabled", () -> true);
    }

    @Autowired CampaignService campaigns;
    @Autowired CampaignCacheInvalidator invalidator;
    @Autowired TransactionTemplate transaction;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry metrics;

    @BeforeEach
    void admin() {
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username='admin-demo'", Long.class);
        var principal = new LoginPrincipal(id, "admin-demo", Role.ADMIN, "unused");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void repeatedDetailUsesCacheAndPublishingEvictsNegativeEntry() {
        long id = draft();
        double before = reads();
        assertThatThrownBy(() -> campaigns.publicDetail(id)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> campaigns.publicDetail(id)).isInstanceOf(BusinessException.class);
        assertThat(reads() - before).isEqualTo(1);
        campaigns.publish(id);
        assertThat(campaigns.publicDetail(id).status()).isEqualTo(CampaignStatus.ACTIVE);
        campaigns.publicDetail(id);
        assertThat(reads() - before).isEqualTo(2);
        campaigns.disable(id);
        assertThat(campaigns.publicDetail(id).status()).isEqualTo(CampaignStatus.DISABLED);
        assertThat(reads() - before).isEqualTo(3);
    }

    @Test
    void rolledBackMutationKeepsExistingCacheEntry() {
        long id = draft();
        campaigns.publish(id);
        campaigns.publicDetail(id);
        String key = "vf:campaign:detail:v1:" + id;
        String cached = redis.opsForValue().get(key);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE campaigns SET name='uncommitted' WHERE id=?", id);
            invalidator.evictAfterCommit(id);
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get(key)).isEqualTo(cached);
        assertThat(campaigns.publicDetail(id).name()).isNotEqualTo("uncommitted");
    }

    private double reads() { return metrics.counter("voucherflow.cache.database.reads").count(); }

    private long draft() {
        Instant now = Instant.now();
        return Long.parseLong(campaigns.create(new CampaignDtos.CreateRequest("cache-" + UUID.randomUUID(), "", 10,
                now.minusSeconds(60), now.plusSeconds(120), now.plusSeconds(240),
                FulfillmentMode.LOCAL, null, null, null)).id());
    }
}
