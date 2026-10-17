package com.dameokja.backend.analysis.domain;

import com.dameokja.backend.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 이미지 한 장을 AI에 전달하는 작업이다. AI 접수가 아니라 콜백 저장이 끝나야 DONE이 된다.
@Getter
@Entity
@Table(name = "analysis_dispatches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisDispatch extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_dispatch_id")
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36, updatable = false)
    private String requestId;

    @Column(name = "analysis_job_id", nullable = false, length = 36, updatable = false)
    private String analysisJobId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "object_key", nullable = false, length = 512, updatable = false)
    private String objectKey;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(name = "input_hint", nullable = false, length = 16, updatable = false)
    private String inputHint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisDispatchStatus status;

    @Column(name = "ai_analysis_id", length = 64)
    private String aiAnalysisId;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_ai_status", length = 20)
    private String lastAiStatus;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public static AnalysisDispatch pending(String analysisJobId, Long userId, String inputHint, AnalysisImage image,
            LocalDateTime now, LocalDateTime expiresAt) {
        AnalysisDispatch dispatch = new AnalysisDispatch();
        dispatch.requestId = image.requestId();
        dispatch.analysisJobId = analysisJobId;
        dispatch.userId = userId;
        dispatch.objectKey = image.objectKey();
        dispatch.sha256 = image.sha256();
        dispatch.inputHint = inputHint;
        dispatch.status = AnalysisDispatchStatus.PENDING;
        dispatch.attemptCount = 0;
        dispatch.nextAttemptAt = now;
        dispatch.expiresAt = expiresAt;
        return dispatch;
    }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }
}
