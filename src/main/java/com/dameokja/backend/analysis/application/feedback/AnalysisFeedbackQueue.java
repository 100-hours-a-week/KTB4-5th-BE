package com.dameokja.backend.analysis.application.feedback;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.analysis.infrastructure.AnalysisFeedbackRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 선점 뒤 갱신은 선점 토큰을 조건으로 한다. 변경 0건은 선점 기한이 지나 다른 서버가 다시 가져갔다는 뜻이다.
@Service
@Transactional
@RequiredArgsConstructor
public class AnalysisFeedbackQueue {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final AnalysisFeedbackRepository analysisFeedbackRepository;
    private final AnalysisFeedbackProperties analysisFeedbackProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void enqueue(String aiAnalysisId, AiAnalysisFeedbackRequest feedbackRequest) {
        LocalDateTime now = BusinessTime.now(clock);
        analysisFeedbackRepository.save(AnalysisFeedback.pending(aiAnalysisId, objectMapper.writeValueAsString(feedbackRequest),
                now, now.plus(analysisFeedbackProperties.expiresAfter())));
    }

    // 상태 인덱스를 바꾸는 선점끼리 갭 잠금으로 교착되지 않도록 이 짧은 트랜잭션만 READ COMMITTED로 실행한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<LeasedFeedback> leaseDue() {
        LocalDateTime now = BusinessTime.now(clock);
        List<Long> feedbackIds = new ArrayList<>(analysisFeedbackRepository.findLeaseExpiredIdsWithSkipLock(sqlTime(now), analysisFeedbackProperties.batchSize()));
        feedbackIds.addAll(analysisFeedbackRepository.findDueIdsWithSkipLock(sqlTime(now), analysisFeedbackProperties.batchSize() - feedbackIds.size()));
        if (feedbackIds.isEmpty()) {
            return List.of();
        }
        String leaseToken = UUID.randomUUID().toString();
        analysisFeedbackRepository.markSending(feedbackIds, leaseToken, sqlTime(now), sqlTime(now.plus(analysisFeedbackProperties.leaseDuration())));
        return feedbackIds.stream().map(id -> new LeasedFeedback(id, leaseToken)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisFeedback> findLeased(LeasedFeedback leasedFeedback) {
        return analysisFeedbackRepository.findByIdAndLeaseToken(leasedFeedback.id(), leasedFeedback.token());
    }

    public void finish(LeasedFeedback leasedFeedback) {
        analysisFeedbackRepository.finish(leasedFeedback.id(), leasedFeedback.token(), sqlTime(BusinessTime.now(clock)));
    }

    public void retryLater(LeasedFeedback leasedFeedback, AnalysisFeedbackErrorCode errorCode) {
        LocalDateTime now = BusinessTime.now(clock);
        analysisFeedbackRepository.reschedule(leasedFeedback.id(), leasedFeedback.token(), sqlTime(now.plus(analysisFeedbackProperties.retryDelay())),
                errorCode.name(), sqlTime(now));
    }

    public void fail(LeasedFeedback leasedFeedback, AnalysisFeedbackErrorCode errorCode) {
        analysisFeedbackRepository.fail(leasedFeedback.id(), leasedFeedback.token(), errorCode.name(), sqlTime(BusinessTime.now(clock)));
    }

    public void failExpired() {
        analysisFeedbackRepository.failExpired(AnalysisFeedbackErrorCode.EXPIRED.name(), sqlTime(BusinessTime.now(clock)));
    }

    // native 파라미터의 Timestamp 변환을 피하고 서울 벽시각을 DATETIME으로 그대로 전달한다.
    private String sqlTime(LocalDateTime value) { return value.format(SQL_TIME); }
}
