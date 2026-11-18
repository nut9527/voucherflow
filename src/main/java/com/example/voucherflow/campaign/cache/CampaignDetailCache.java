package com.example.voucherflow.campaign.cache;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/** 读写 Redis 活动详情缓存，使用短期空值缓存和随机过期抖动分散回源。 */
@Component
public class CampaignDetailCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CacheProperties config;
    private final MeterRegistry metrics;

    public CampaignDetailCache(
            StringRedisTemplate redis,
            ObjectMapper json,
            CacheProperties config,
            MeterRegistry metrics) {
        this.redis = redis;
        this.json = json;
        this.config = config;
        this.metrics = metrics;
    }

    /** 返回缓存条目；空值条目代表活动不存在，空 Optional 代表需要回源。 */
    public Optional<CampaignCacheEntry> get(long id) {
        try {
            String value = redis.opsForValue().get(key(id));
            if (value == null) {
                count("miss");
                return Optional.empty();
            }
            var entry = json.readValue(value, CampaignCacheEntry.class);
            if (entry == null || (entry.metadata() != null && entry.metadata().id() != id)) {
                throw new IllegalArgumentException("Campaign cache identity mismatch");
            }
            count(entry.kind() == CampaignCacheEntry.Kind.FOUND ? "hit" : "negative_hit");
            return Optional.of(entry);
        } catch (RuntimeException ex) {
            // 缓存故障按未命中处理，由读取服务的有界回源继续提供查询。
            count("error");
            return Optional.empty();
        }
    }

    public void put(long id, Optional<CampaignMetadata> value) {
        try {
            // 不存在的活动短暂缓存；正常条目增加随机抖动，分散集中失效的压力。
            var ttl = value.isEmpty()
                    ? config.negativeTtl()
                    : config.ttl()
                            .plusMillis(
                                    ThreadLocalRandom.current()
                                            .nextLong(config.jitter().toMillis() + 1));
            redis.opsForValue()
                    .set(key(id), json.writeValueAsString(CampaignCacheEntry.of(value)), ttl);
        } catch (RuntimeException ex) {
            count("write_error");
        }
    }

    public void evict(long id) {
        if (!config.enabled()) {
            return;
        }
        try {
            redis.delete(key(id));
        } catch (RuntimeException ex) {
            count("evict_failure");
        }
    }

    private void count(String outcome) {
        metrics.counter("voucherflow.cache.requests", "outcome", outcome).increment();
    }

    private String key(long id) {
        return "vf:campaign:detail:v1:" + id;
    }
}
