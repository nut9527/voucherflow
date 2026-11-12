package com.example.voucherflow.campaign.cache;

import java.util.Optional;

/** 缓存封装同时记录格式版本和查询结果，区分缓存未命中与活动不存在。 */
public record CampaignCacheEntry(int schemaVersion, Kind kind, CampaignMetadata metadata) {
    public enum Kind { FOUND, MISSING }

    public CampaignCacheEntry {
        if (schemaVersion != 1 || kind == null || (kind == Kind.FOUND) != (metadata != null)) {
            throw new IllegalArgumentException("Unsupported campaign cache entry");
        }
    }

    public static CampaignCacheEntry of(Optional<CampaignMetadata> value) {
        return new CampaignCacheEntry(1, value.isPresent() ? Kind.FOUND : Kind.MISSING, value.orElse(null));
    }
}
