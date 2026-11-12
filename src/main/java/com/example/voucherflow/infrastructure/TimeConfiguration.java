package com.example.voucherflow.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 提供统一的 UTC 时钟，供业务计算时间并便于测试替换。 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
