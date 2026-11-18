package com.example.voucherflow.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.voucherflow.campaign.cache.CampaignCacheInvalidator;
import com.example.voucherflow.campaign.cache.CampaignDetailCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 验证缓存仅在业务事务提交后失效，回滚时保留原缓存。 */
class CampaignCacheInvalidatorTest {
    private final CampaignDetailCache cache = mock(CampaignDetailCache.class);
    private final CampaignCacheInvalidator invalidator = new CampaignCacheInvalidator(cache);

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void evictionRunsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        invalidator.evictAfterCommit(7);
        verifyNoInteractions(cache);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(cache).evict(7);
    }

    @Test
    void rollbackDoesNotEvict() {
        TransactionSynchronizationManager.initSynchronization();
        invalidator.evictAfterCommit(7);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(
                        sync ->
                                sync.afterCompletion(
                                        TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(cache);
    }

    @Test
    void missingTransactionIsRejected() {
        assertThatThrownBy(() -> invalidator.evictAfterCommit(7))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(cache);
    }
}
