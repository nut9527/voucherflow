package com.example.voucherflow.protection;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 通过 Redis Lua 原子计数执行固定窗口限流，并记录各入口的准入指标。 */
@Component
public class RedisRateLimiter {
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final DefaultRedisScript<List> script;

    public RedisRateLimiter(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis; this.metrics = metrics;
        this.script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/fixed-window.lua"));
        script.setResultType(List.class);
    }

    /** 执行窗口计数；拒绝时保留剩余有效期，供客户端安排下一次尝试。 */
    public void acquire(String key, int limit, Duration window, String scope) {
        List<?> result;
        try {
            result = redis.execute(script, List.of(key), Integer.toString(limit), Long.toString(window.toMillis()));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Long)
                    || !(result.get(1) instanceof Long) || (long) result.get(1) < 0) {
                throw new IllegalStateException("Invalid rate limit script result");
            }
        } catch (RuntimeException ex) {
            count(scope, "unavailable");
            throw AdmissionException.unavailable();
        }
        if ((long) result.get(0) == 0) {
            count(scope, "rejected");
            throw AdmissionException.throttled((long) result.get(1));
        }
        count(scope, "allowed");
    }

    private void count(String scope, String outcome) {
        metrics.counter("voucherflow.admission.requests", "scope", scope, "outcome", outcome).increment();
    }
}
