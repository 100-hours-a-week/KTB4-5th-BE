package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AnalysisKafkaProperties.class, AnalysisOutboxProperties.class})
public class AnalysisKafkaConfig {
    // 공용 스케줄러의 자동구성을 유지하면서 브로커 응답 대기로 다른 배치가 지연되지 않게 한다.
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler analysisOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("analysis-outbox-");
        return scheduler;
    }
}
