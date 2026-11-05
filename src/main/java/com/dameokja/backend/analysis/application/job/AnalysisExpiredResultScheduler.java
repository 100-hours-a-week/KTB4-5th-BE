package com.dameokja.backend.analysis.application.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "analysis.result-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisExpiredResultScheduler {
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final int batchSize;

    public AnalysisExpiredResultScheduler(AnalysisJobStateAccess analysisJobStateAccess,
            @Value("${analysis.result-cleanup.batch-size:100}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("analysis.result-cleanup.batch-size must be positive");
        }
        this.analysisJobStateAccess = analysisJobStateAccess;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${analysis.result-cleanup.poll-interval:1m}")
    public void poll() {
        for (int deletedJobCount = 0; deletedJobCount < batchSize && !Thread.currentThread().isInterrupted(); deletedJobCount++) {
            if (!analysisJobStateAccess.deleteNextExpired()) {
                break;
            }
        }
    }
}
