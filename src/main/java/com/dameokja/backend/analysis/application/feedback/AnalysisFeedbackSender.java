package com.dameokja.backend.analysis.application.feedback;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisFeedbackSender {
    private final AnalysisFeedbackQueue analysisFeedbackQueue;
    private final AiAnalysisClient aiAnalysisClient;
    private final ObjectMapper objectMapper;

    public void sendDue() {
        analysisFeedbackQueue.failExpired();
        // AI가 꺼져 있는 밤에는 선점하지 않아 시도 횟수와 다음 시도 시각을 소모하지 않는다.
        if (!aiAnalysisClient.ready()) {
            return;
        }
        analysisFeedbackQueue.leaseDue().forEach(this::send);
    }

    private void send(LeasedFeedback leasedFeedback) {
        analysisFeedbackQueue.findLeased(leasedFeedback).ifPresent(analysisFeedback -> send(leasedFeedback, analysisFeedback));
    }

    private void send(LeasedFeedback leasedFeedback, AnalysisFeedback analysisFeedback) {
        try {
            aiAnalysisClient.feedback(analysisFeedback.getAiAnalysisId(), objectMapper.readValue(analysisFeedback.getFeedbackBody(), AiAnalysisFeedbackRequest.class));
            analysisFeedbackQueue.finish(leasedFeedback);
        } catch (HttpClientErrorException exception) {
            handleRejection(leasedFeedback, analysisFeedback, exception);
        } catch (RestClientException exception) {
            log.warn("AI 피드백 전송 실패, 다시 보냅니다: analysisFeedbackId={}, aiAnalysisId={}", analysisFeedback.getId(), analysisFeedback.getAiAnalysisId(), exception);
            analysisFeedbackQueue.retryLater(leasedFeedback, AnalysisFeedbackErrorCode.AI_UNAVAILABLE);
        }
    }

    // 429 외의 4xx는 같은 요청을 다시 보내도 받아들여지지 않으므로 바로 실패로 끝낸다.
    private void handleRejection(LeasedFeedback leasedFeedback, AnalysisFeedback analysisFeedback, HttpClientErrorException exception) {
        if (exception.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            analysisFeedbackQueue.retryLater(leasedFeedback, AnalysisFeedbackErrorCode.AI_BUSY);
            return;
        }
        log.warn("AI가 피드백을 거절했습니다: analysisFeedbackId={}, aiAnalysisId={}, status={}",
                analysisFeedback.getId(), analysisFeedback.getAiAnalysisId(), exception.getStatusCode(), exception);
        analysisFeedbackQueue.fail(leasedFeedback, AnalysisFeedbackErrorCode.AI_REJECTED);
    }
}
