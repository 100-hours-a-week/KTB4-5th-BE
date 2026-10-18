package com.dameokja.backend.analysis.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "analysis.feedback.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisFeedbackScheduler {
    private final AnalysisFeedbackSender analysisFeedbackSender;

    @Scheduled(fixedDelayString = "${analysis.feedback.poll-interval:10s}")
    public void poll() {
        analysisFeedbackSender.sendDue();
    }
}
