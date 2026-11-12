package com.example.voucherflow.protection;

import com.example.voucherflow.campaign.cache.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 注册缓存、限流配置及由安全过滤器链管理的登录限流过滤器。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CacheProperties.class, RateLimitProperties.class})
public class ProtectionConfiguration {
    @Bean
    LoginRateLimitFilter loginRateLimitFilter(RateLimitProperties config, RedisRateLimiter limiter, ObjectMapper json) {
        return new LoginRateLimitFilter(config, limiter, json);
    }

    @Bean
    FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitRegistration(LoginRateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // 过滤器仅加入 Spring Security 链，避免被 Servlet 容器再执行一次。
        registration.setEnabled(false);
        return registration;
    }
}
