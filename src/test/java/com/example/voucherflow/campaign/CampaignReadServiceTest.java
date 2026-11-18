package com.example.voucherflow.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.voucherflow.campaign.cache.*;
import com.example.voucherflow.campaign.mapper.CampaignMapper;
import com.example.voucherflow.campaign.model.CampaignPhase;
import com.example.voucherflow.campaign.model.CampaignStatus;
import com.example.voucherflow.campaign.model.FulfillmentMode;
import com.example.voucherflow.common.BusinessException;
import com.example.voucherflow.common.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证详情回源的缓存复查、空值缓存、异常处理及动态活动阶段计算。 */
class CampaignReadServiceTest {
    private final CampaignMapper mapper = mock(CampaignMapper.class);
    private final CampaignDetailCache cache = mock(CampaignDetailCache.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Instant start = Instant.parse("2020-01-01T00:00:00Z");
    private final CampaignMetadata metadata =
            new CampaignMetadata(
                    7,
                    "权益",
                    "说明",
                    20,
                    CampaignStatus.ACTIVE,
                    start,
                    start.plusSeconds(60),
                    start.plusSeconds(120),
                    FulfillmentMode.LOCAL);

    @Test
    void lateLeaderRechecksCacheBeforeDatabase() {
        when(cache.get(7))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(CampaignCacheEntry.of(Optional.of(metadata))));
        assertThat(service(true, start).detail(7).id()).isEqualTo("7");
        verify(cache, times(2)).get(7);
        verifyNoInteractions(mapper);
        assertThat(metrics.counter("voucherflow.cache.database.reads").count()).isZero();
    }

    @Test
    void negativeHitDoesNotQueryDatabase() {
        when(cache.get(7)).thenReturn(Optional.of(CampaignCacheEntry.of(Optional.empty())));
        assertThatThrownBy(() -> service(true, start).detail(7))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(mapper);
    }

    @Test
    void databaseFailureDoesNotPoisonNegativeCache() {
        when(cache.get(7)).thenReturn(Optional.empty());
        when(mapper.findPublicById(7)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service(true, start).detail(7))
                .isInstanceOf(IllegalStateException.class);
        verify(cache, never()).put(anyLong(), any());
    }

    @Test
    void disabledCacheStillQueriesDatabaseWithoutRedis() {
        when(mapper.findPublicById(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(false, start).detail(7))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(cache);
        assertThat(metrics.counter("voucherflow.cache.database.reads").count()).isEqualTo(1);
    }

    @Test
    void cachedMetadataComputesPhaseForEveryResponse() {
        when(cache.get(7)).thenReturn(Optional.of(CampaignCacheEntry.of(Optional.of(metadata))));
        assertThat(service(true, start.minusMillis(1)).detail(7).phase())
                .isEqualTo(CampaignPhase.NOT_STARTED);
        assertThat(service(true, start).detail(7).phase()).isEqualTo(CampaignPhase.CLAIMABLE);
        assertThat(service(true, start.plusSeconds(60)).detail(7).phase())
                .isEqualTo(CampaignPhase.ENDED);
        verifyNoInteractions(mapper);
    }

    private CampaignReadService service(boolean enabled, Instant now) {
        var properties =
                new CacheProperties(
                        enabled,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        true,
                        128,
                        8,
                        Duration.ofMillis(500));
        return new CampaignReadService(
                mapper, cache, properties, Clock.fixed(now, ZoneOffset.UTC), metrics);
    }
}
