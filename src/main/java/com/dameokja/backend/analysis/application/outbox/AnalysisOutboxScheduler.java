package com.dameokja.backend.analysis.application.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "analysis.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisOutboxScheduler {
    private final AnalysisOutboxWorker analysisOutboxWorker;

    @Scheduled(fixedDelayString = "${analysis.outbox.poll-interval:5s}", scheduler = "analysisOutboxTaskScheduler")
    public void poll() { analysisOutboxWorker.publishBatch(); }
}
