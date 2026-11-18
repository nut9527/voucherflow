package com.example.voucherflow.protection;

import com.example.voucherflow.identity.CurrentUser;
import org.springframework.stereotype.Service;

/** 在领取业务执行前，依次检查用户在当前活动的请求额度与活动总额度。 */
@Service
public class ClaimAdmissionService {
    private final CurrentUser user;
    private final RateLimitProperties config;
    private final RedisRateLimiter limiter;

    public ClaimAdmissionService(
            CurrentUser user, RateLimitProperties config, RedisRateLimiter limiter) {
        this.user = user;
        this.config = config;
        this.limiter = limiter;
    }

    public void check(long campaignId) {
        long userId = user.requireUserId();
        if (!config.enabled()) {
            return;
        }
        // 先限制用户在当前活动的重复请求，再占用活动总额度；两个窗口分别计数。
        limiter.acquire(
                "vf:limit:claim:user:" + userId + ":" + campaignId,
                config.userLimit(),
                config.window(),
                "claim_user");
        limiter.acquire(
                "vf:limit:claim:campaign:" + campaignId,
                config.campaignLimit(),
                config.window(),
                "claim_campaign");
    }
}
