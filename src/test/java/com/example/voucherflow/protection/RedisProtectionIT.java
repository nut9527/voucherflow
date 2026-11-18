package com.example.voucherflow.protection;

import static org.assertj.core.api.Assertions.*;

import com.example.voucherflow.campaign.cache.CacheProperties;
import com.example.voucherflow.campaign.cache.CampaignCacheEntry;
import com.example.voucherflow.campaign.cache.CampaignDetailCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实 Redis 验证原子限流、有效期及缓存和准入的不同故障处理策略。 */
@Testcontainers
class RedisProtectionIT {
    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connection = connection(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        redis = new StringRedisTemplate(connection);
    }

    @AfterAll
    static void close() {
        connection.destroy();
    }

    @Test
    void concurrentAdmissionIsAtomicAndHasExpiry() throws Exception {
        var limiter = new RedisRateLimiter(redis, new SimpleMeterRegistry());
        String key = "vf:test:limit:" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures =
                    java.util.stream.IntStream.range(0, 24)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        try {
                                                            limiter.acquire(
                                                                    key,
                                                                    4,
                                                                    Duration.ofSeconds(30),
                                                                    "test");
                                                            allowed.incrementAndGet();
                                                        } catch (AdmissionException ex) {
                                                            assertThat(ex.status()).isEqualTo(429);
                                                            assertThat(ex.retryAfterSeconds())
                                                                    .isBetween(1L, 30L);
                                                            rejected.incrementAndGet();
                                                        }
                                                        return null;
                                                    }))
                            .toList();
            start.countDown();
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertThat(allowed).hasValue(4);
            assertThat(rejected).hasValue(20);
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 30000L);
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void counterWithoutExpiryIsRepairedByTheScript() {
        String key = "vf:test:limit:" + UUID.randomUUID();
        redis.opsForValue().set(key, "1");
        try {
            new RedisRateLimiter(redis, new SimpleMeterRegistry())
                    .acquire(key, 4, Duration.ofSeconds(30), "test");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 30000L);
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void missingCacheEntryExpiresAndMalformedCacheFallsBack() {
        var metrics = new SimpleMeterRegistry();
        var cache = cache(redis, metrics);
        String key = "vf:campaign:detail:v1:7654321";
        try {
            assertThat(cache.get(7654321)).isEmpty();
            cache.put(7654321, Optional.empty());
            assertThat(cache.get(7654321).orElseThrow().kind())
                    .isEqualTo(CampaignCacheEntry.Kind.MISSING);
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 5000L);
            redis.opsForValue().set(key, "{\"schemaVersion\":100}");
            assertThat(cache.get(7654321)).isEmpty();
            assertThat(metrics.counter("voucherflow.cache.requests", "outcome", "error").count())
                    .isEqualTo(1);
            cache.evict(7654321);
            assertThat(redis.hasKey(key)).isFalse();
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void redisFailureAllowsCacheFallbackButRejectsAdmission() {
        var unavailable = connection("127.0.0.1", 1);
        try {
            var disconnected = new StringRedisTemplate(unavailable);
            var cache = cache(disconnected, new SimpleMeterRegistry());
            assertThat(cache.get(42)).isEmpty();
            assertThatCode(() -> cache.put(42, Optional.empty())).doesNotThrowAnyException();
            var limiter = new RedisRateLimiter(disconnected, new SimpleMeterRegistry());
            assertThatThrownBy(
                            () ->
                                    limiter.acquire(
                                            "vf:test:offline", 1, Duration.ofSeconds(1), "test"))
                    .isInstanceOfSatisfying(
                            AdmissionException.class,
                            ex -> {
                                assertThat(ex.status()).isEqualTo(503);
                                assertThat(ex.response().code())
                                        .isEqualTo("RATE_LIMIT_UNAVAILABLE");
                            });
        } finally {
            unavailable.destroy();
        }
    }

    private static CampaignDetailCache cache(
            StringRedisTemplate template, SimpleMeterRegistry metrics) {
        var config =
                new CacheProperties(
                        true,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        true,
                        128,
                        8,
                        Duration.ofMillis(500));
        return new CampaignDetailCache(template, JsonMapper.builder().build(), config, metrics);
    }

    private static LettuceConnectionFactory connection(String host, int port) {
        var client =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(200))
                        .shutdownTimeout(Duration.ZERO)
                        .build();
        var factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
