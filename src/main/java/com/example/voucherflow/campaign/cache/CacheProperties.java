package com.example.voucherflow.campaign.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 配置活动缓存寿命、空值缓存和合并回源的容量及等待时间。 */
@ConfigurationProperties("voucherflow.cache")
public record CacheProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("30s") Duration ttl, @DefaultValue("10s") Duration jitter,
        @DefaultValue("5s") Duration negativeTtl, @DefaultValue("true") boolean coalescingEnabled,
        @DefaultValue("128") int maxKeys, @DefaultValue("8") int dbConcurrency,
        @DefaultValue("500ms") Duration waitTimeout) {
    public CacheProperties {
        if (ttl.toMillis() < 1 || ttl.compareTo(Duration.ofHours(1)) > 0
                || jitter.isNegative() || jitter.compareTo(ttl) > 0
                || negativeTtl.toMillis() < 1 || negativeTtl.compareTo(ttl) > 0
                || maxKeys < 1 || maxKeys > 10000 || dbConcurrency < 1 || dbConcurrency > maxKeys
                || waitTimeout.toMillis() < 1 || waitTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Invalid campaign cache capacity or duration");
        }
    }
}
