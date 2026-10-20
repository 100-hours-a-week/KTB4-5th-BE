package com.dameokja.backend.analysis.application.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "analysis.notification.retry-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisPendingNotificationScheduler {
    private final AnalysisCompletionNotifier analysisCompletionNotifier;

    @Scheduled(fixedDelayString = "${analysis.notification.poll-interval:1s}")
    public void poll() {
        analysisCompletionNotifier.deliverPending();
    }
}
