package com.example.voucherflow.protection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 配置领取与登录的固定窗口限流额度和窗口时长。 */
@ConfigurationProperties("voucherflow.rate-limit")
public record RateLimitProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("2") int userLimit, @DefaultValue("100") int campaignLimit,
        @DefaultValue("10") int loginLimit, @DefaultValue("1s") Duration window,
        @DefaultValue("1m") Duration loginWindow) {
    public RateLimitProperties {
        if (userLimit < 1 || campaignLimit < 1 || loginLimit < 1
                || window.toMillis() < 1 || window.compareTo(Duration.ofHours(1)) > 0
                || loginWindow.toMillis() < 1 || loginWindow.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid rate limit configuration");
        }
    }
}
