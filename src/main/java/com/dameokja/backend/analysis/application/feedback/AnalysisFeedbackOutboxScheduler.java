package com.dameokja.backend.analysis.application.feedback;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 요청 outbox와 별도 스레드에서 실행해 피드백 적체가 요청 발행 슬롯을 차지하지 않게 한다.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "analysis.feedback.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisFeedbackOutboxScheduler {
    private final AnalysisFeedbackOutboxWorker analysisFeedbackOutboxWorker;

    @Scheduled(fixedDelayString = "${analysis.feedback.poll-interval:10s}", scheduler = "analysisFeedbackTaskScheduler")
    public void poll() {
        analysisFeedbackOutboxWorker.sendDue();
    }
}
