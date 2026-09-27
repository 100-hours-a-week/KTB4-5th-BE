package com.dameokja.backend.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock applicationClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
}
