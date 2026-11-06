package com.dameokja.backend.recipe.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "recommendation.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class RecommendationOutboxScheduler {
    private final RecommendationOutboxWorker recommendationOutboxWorker;

    @Scheduled(fixedDelayString = "${recommendation.outbox.poll-interval:5s}", scheduler = "recommendationOutboxTaskScheduler")
    public void poll() {
        recommendationOutboxWorker.publishBatch();
    }

    @Bean(defaultCandidate = false)
    static ThreadPoolTaskScheduler recommendationOutboxTaskScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("recommendation-outbox-");
        return taskScheduler;
    }
}
