package com.dameokja.backend.global.config;

import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock applicationClock() { return Clock.system(BusinessTime.ZONE); }
}
