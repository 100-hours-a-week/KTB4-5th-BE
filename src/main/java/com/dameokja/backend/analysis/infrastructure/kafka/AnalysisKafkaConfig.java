package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AnalysisKafkaProperties.class, AnalysisOutboxProperties.class})
public class AnalysisKafkaConfig {
    // 공용 스케줄러의 자동구성을 유지하면서 발행 대기로 인한 다른 배치 지연을 분리한다.
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler analysisOutboxTaskScheduler() {
        ThreadPoolTaskScheduler analysisOutboxTaskScheduler = new ThreadPoolTaskScheduler();
        analysisOutboxTaskScheduler.setPoolSize(1);
        analysisOutboxTaskScheduler.setThreadNamePrefix("analysis-outbox-");
        return analysisOutboxTaskScheduler;
    }
}
