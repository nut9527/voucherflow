package com.example.voucherflow.outbox;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 消息连接与执行配置，集中定义发布开关、租约、重试和消费并发参数。 */
@Validated
@ConfigurationProperties("voucherflow.messaging")
public record MessagingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean publishingEnabled,
        @DefaultValue("127.0.0.1:8081") @NotBlank String endpoints,
        @DefaultValue("false") boolean tls,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("4") @Min(1) @Max(32) int sendThreads,
        @DefaultValue("2") @Min(1) @Max(16) int consumerThreads,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("10") @Min(1) @Max(100) int maxAttempts,
        @DefaultValue("30s") Duration invisibleDuration,
        @DefaultValue("15s") Duration shutdownTimeout) {
    public MessagingProperties {
        if (lease != null
                && requestTimeout != null
                && lease.compareTo(requestTimeout.multipliedBy(3)) <= 0) {
            throw new IllegalArgumentException(
                    "Messaging lease must exceed three request timeouts");
        }
        if (requestTimeout != null && (requestTimeout.isNegative() || requestTimeout.isZero())) {
            throw new IllegalArgumentException("Messaging request timeout must be positive");
        }
        if (invisibleDuration != null && invisibleDuration.compareTo(Duration.ofSeconds(10)) < 0) {
            throw new IllegalArgumentException(
                    "Messaging invisibility must be at least ten seconds");
        }
    }
}
