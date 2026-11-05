package com.dameokja.backend.analysis.application.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class AnalysisNotificationConfig {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler analysisNotificationTaskScheduler() {
        ThreadPoolTaskScheduler analysisNotificationTaskScheduler = new ThreadPoolTaskScheduler();
        analysisNotificationTaskScheduler.setPoolSize(1);
        analysisNotificationTaskScheduler.setThreadNamePrefix("analysis-notification-");
        return analysisNotificationTaskScheduler;
    }
}
