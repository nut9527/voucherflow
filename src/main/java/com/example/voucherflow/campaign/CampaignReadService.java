package com.example.voucherflow.campaign;

import com.example.voucherflow.campaign.cache.CacheProperties;
import com.example.voucherflow.campaign.cache.CampaignDetailCache;
import com.example.voucherflow.campaign.cache.CampaignMetadata;
import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import com.example.voucherflow.protection.BoundedSingleFlight;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 读取公开活动详情，通过缓存和同键请求合并控制数据库回源。 */
@Service
public class CampaignReadService {
    private final CampaignMapper mapper;
    private final CampaignDetailCache cache;
    private final CacheProperties config;
    private final Clock clock;
    private final Counter databaseReads;
    private final BoundedSingleFlight<Long, Optional<CampaignMetadata>> flights;

    public CampaignReadService(CampaignMapper mapper, CampaignDetailCache cache, CacheProperties config,
            Clock clock, MeterRegistry metrics) {
        this.mapper = mapper; this.cache = cache; this.config = config; this.clock = clock;
        this.databaseReads = metrics.counter("voucherflow.cache.database.reads");
        this.flights = new BoundedSingleFlight<>(config.maxKeys(), config.dbConcurrency(),
                config.waitTimeout(), config.coalescingEnabled());
    }

    /** 优先使用缓存；缓存未命中时由同键共享加载过程查询数据库。 */
    public CampaignDtos.Detail detail(long id) {
        if (config.enabled()) {
            var cached = cache.get(id);
            if (cached.isPresent()) { return detail(Optional.ofNullable(cached.get().metadata())); }
        }
        return detail(flights.load(id, () -> {
            // 等待回源资格期间其他请求可能已填充缓存，执行查询前再检查一次。
            if (config.enabled()) {
                var cached = cache.get(id);
                if (cached.isPresent()) { return Optional.ofNullable(cached.get().metadata()); }
            }
            databaseReads.increment();
            var value = mapper.findPublicById(id).map(CampaignMetadata::fromRow);
            if (config.enabled()) { cache.put(id, value); }
            return value;
        }));
    }

    private CampaignDtos.Detail detail(Optional<CampaignMetadata> value) {
        return value.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).toDetail(clock);
    }
}
