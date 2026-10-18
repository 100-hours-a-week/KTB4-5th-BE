package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisFeedbackRequest;
import com.dameokja.backend.analysis.infrastructure.AnalysisFeedbackRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 선점 뒤 갱신은 선점 토큰을 조건으로 한다. 변경 0건은 선점 기한이 지나 다른 서버가 다시 가져갔다는 뜻이다.
@Service
@Transactional
@RequiredArgsConstructor
public class AnalysisFeedbackQueue {
    private final AnalysisFeedbackRepository analysisFeedbackRepository;
    private final AnalysisFeedbackProperties analysisFeedbackProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void enqueue(String aiAnalysisId, AiAnalysisFeedbackRequest feedbackRequest) {
        LocalDateTime now = BusinessTime.now(clock);
        analysisFeedbackRepository.save(AnalysisFeedback.pending(aiAnalysisId, feedbackRequest.itemId(), objectMapper.writeValueAsString(feedbackRequest),
                now, now.plus(analysisFeedbackProperties.expiresAfter())));
    }

    public List<LeasedFeedback> leaseDue() {
        LocalDateTime now = BusinessTime.now(clock);
        List<Long> feedbackIds = new ArrayList<>(analysisFeedbackRepository.findLeaseExpiredIdsWithSkipLock(now, analysisFeedbackProperties.batchSize()));
        feedbackIds.addAll(analysisFeedbackRepository.findDueIdsWithSkipLock(now, analysisFeedbackProperties.batchSize() - feedbackIds.size()));
        if (feedbackIds.isEmpty()) {
            return List.of();
        }
        String leaseToken = UUID.randomUUID().toString();
        analysisFeedbackRepository.markSending(feedbackIds, leaseToken, now, now.plus(analysisFeedbackProperties.leaseDuration()));
        return feedbackIds.stream().map(id -> new LeasedFeedback(id, leaseToken)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisFeedback> findLeased(LeasedFeedback leasedFeedback) {
        return analysisFeedbackRepository.findByIdAndLeaseToken(leasedFeedback.id(), leasedFeedback.token());
    }

    public void finish(LeasedFeedback leasedFeedback) {
        analysisFeedbackRepository.finish(leasedFeedback.id(), leasedFeedback.token(), BusinessTime.now(clock));
    }

    public void retryLater(LeasedFeedback leasedFeedback, AnalysisFeedbackErrorCode errorCode) {
        LocalDateTime now = BusinessTime.now(clock);
        analysisFeedbackRepository.reschedule(leasedFeedback.id(), leasedFeedback.token(), now.plus(analysisFeedbackProperties.retryDelay()), errorCode.name(), now);
    }

    public void fail(LeasedFeedback leasedFeedback, AnalysisFeedbackErrorCode errorCode) {
        analysisFeedbackRepository.fail(leasedFeedback.id(), leasedFeedback.token(), errorCode.name(), BusinessTime.now(clock));
    }

    public void failExpired() {
        analysisFeedbackRepository.failExpired(AnalysisFeedbackErrorCode.EXPIRED.name(), BusinessTime.now(clock));
    }
}
