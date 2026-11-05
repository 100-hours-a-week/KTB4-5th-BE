package com.dameokja.backend.analysis.application.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "analysis.notification.retry-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisDatabaseNotificationScheduler {
    private final AnalysisDatabaseCompletionNotifier analysisDatabaseCompletionNotifier;

    @Scheduled(fixedDelayString = "${analysis.notification.poll-interval:1s}", scheduler = "analysisNotificationTaskScheduler")
    public void poll() {
        analysisDatabaseCompletionNotifier.deliverPending();
    }
}
