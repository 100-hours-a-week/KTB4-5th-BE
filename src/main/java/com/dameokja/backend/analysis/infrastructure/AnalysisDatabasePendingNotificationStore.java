package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Repository
@Transactional
public class AnalysisDatabasePendingNotificationStore {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final AnalysisJobStateRepository analysisJobStateRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration retryDelay;
    private final Duration leaseDuration;
    private final int sseMaxAttempts;

    public AnalysisDatabasePendingNotificationStore(AnalysisJobStateRepository analysisJobStateRepository, ObjectMapper objectMapper, Clock clock,
            @Value("${analysis.notification.retry-delay:5s}") Duration retryDelay, @Value("${analysis.notification.lease-duration:30s}") Duration leaseDuration,
            @Value("${analysis.notification.sse-max-attempts:3}") int sseMaxAttempts) {
        if (retryDelay == null || retryDelay.toMillis() < 1 || leaseDuration == null || leaseDuration.toMillis() < 1 || sseMaxAttempts < 1) {
            throw new IllegalArgumentException("알림 재시도 간격·선점 기간은 1밀리초 이상, 최대 시도 횟수는 1 이상이어야 합니다.");
        }
        this.analysisJobStateRepository = analysisJobStateRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.leaseDuration = leaseDuration;
        this.sseMaxAttempts = sseMaxAttempts;
    }

    // 인덱스 범위를 읽은 뒤 시각을 갱신하므로 갭락을 피하고, 외부 발행 전에 트랜잭션을 끝낸다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Lease> leaseNext() {
        return analysisJobStateRepository.findDueNotificationForUpdate(BusinessTime.now(clock).format(SQL_TIME)).map(analysisJobState -> {
            String leaseToken = UUID.randomUUID().toString();
            analysisJobState.leaseNotification(leaseToken, BusinessTime.now(clock).plus(leaseDuration));
            return new Lease(analysisJobState.getId(), leaseToken, analysisJobState.getUser().getId());
        });
    }

    public int claimSseAttempt(Lease lease) { return requireLease(lease).claimSseAttempt(sseMaxAttempts); }

    public boolean claimImageEventAttempt(Lease lease, String requestId) { return requireLease(lease).claimImageEventAttempt(requestId, sseMaxAttempts); }

    @Transactional(propagation = Propagation.MANDATORY)
    public AnalysisJobState requireLease(Lease lease) {
        AnalysisJobState analysisJobState = analysisJobStateRepository.findForUpdate(lease.analysisJobId())
                .orElseThrow(() -> new CustomException(AnalysisExceptionCode.NOT_FOUND));
        if (!ownsActiveLease(analysisJobState, lease, BusinessTime.now(clock))) {
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        return analysisJobState;
    }

    public void completeAttempt(Lease lease) {
        AnalysisJobState analysisJobState = analysisJobStateRepository.findForUpdate(lease.analysisJobId()).orElse(null);
        LocalDateTime currentTime = BusinessTime.now(clock);
        if (analysisJobState == null || !ownsActiveLease(analysisJobState, lease, currentTime)) { return; }
        boolean expired = analysisJobState.getExpiresAt() != null && !currentTime.isBefore(analysisJobState.getExpiresAt());
        // 선점 후 도착한 다른 이미지의 완료 결과도 잠금 안에서 다시 확인해 대기를 지우지 않는다.
        analysisJobState.completeNotificationAttempt(expired || delivered(analysisJobState) ? null : currentTime.plus(retryDelay));
    }

    private boolean ownsActiveLease(AnalysisJobState analysisJobState, Lease lease, LocalDateTime currentTime) {
        return lease.token().equals(analysisJobState.getNotificationLeaseToken()) && analysisJobState.getNotificationNextAttemptAt() != null
                && currentTime.isBefore(analysisJobState.getNotificationNextAttemptAt());
    }

    private boolean delivered(AnalysisJobState analysisJobState) {
        AnalysisJob analysisJob = objectMapper.readerFor(AnalysisJob.class).without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .readValue(analysisJobState.getAnalysisJobBody());
        return !analysisJob.notificationPending() && (analysisJob.notificationChannel() == AnalysisNotificationChannel.PUSH
                || analysisJob.analysisImages().stream().allMatch(analysisImage -> analysisImage.completion() == null || analysisJob.imageEventPublished(analysisImage.requestId())
                || analysisJobState.getNotificationImageAttemptCounts().getOrDefault(analysisImage.requestId(), 0) >= sseMaxAttempts));
    }

    public record Lease(String analysisJobId, String token, Long userId) { }
}
