package com.dameokja.backend.analysis.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "analysis.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisDispatchScheduler {
    private final AnalysisDispatchWorker analysisDispatchWorker;

    @Scheduled(fixedDelayString = "${analysis.dispatch.poll-interval:1s}")
    public void poll() {
        analysisDispatchWorker.poll();
    }
}
