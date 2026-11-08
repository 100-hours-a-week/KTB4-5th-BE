package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "recommendation.daily-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class RecommendationDailyRefreshScheduler {
    private final RecommendationDailyRefreshService refresh;
    private final Clock clock;
    private LocalDate registeredDate;

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul", scheduler = "recommendationDailyTaskScheduler")
    public void refreshAtSeven() { poll(); }

    @Scheduled(fixedDelayString = "${recommendation.daily-refresh.poll-interval:10s}", scheduler = "recommendationDailyTaskScheduler")
    public synchronized void poll() {
        LocalDateTime now = BusinessTime.now(clock);
        if (now.toLocalTime().isBefore(LocalTime.of(7, 0))) { return; }
        if (!now.toLocalDate().equals(registeredDate)) {
            refresh.registerTargets(now.toLocalDate());
            registeredDate = now.toLocalDate();
        }
        refresh.processBatch(now.toLocalDate());
    }

    @Bean(defaultCandidate = false)
    static ThreadPoolTaskScheduler recommendationDailyTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("recommendation-daily-");
        return scheduler;
    }
}
