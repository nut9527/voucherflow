package com.example.voucherflow.campaign.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 将活动缓存清理注册到数据库事务提交之后。 */
@Component
public class CampaignCacheInvalidator {
    private final CampaignDetailCache cache;
    public CampaignCacheInvalidator(CampaignDetailCache cache) { this.cache = cache; }

    /** 提交后再清理，避免事务回滚时删除缓存或提前回源读到尚未更新的数据。 */
    public void evictAfterCommit(long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Campaign invalidation requires a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evict(id); }
        });
    }
}
