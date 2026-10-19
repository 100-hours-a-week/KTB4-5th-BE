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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 재고 등록 뒤 AI에 보낼 품목 하나의 피드백이다. 분석 결과는 등록 직후 지우므로 사용자가 확정한 값을 본문에 그대로 담는다.
@Getter
@Entity
@Table(name = "analysis_feedbacks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisFeedback extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_feedback_id")
    private Long id;

    @Column(name = "ai_analysis_id", nullable = false, length = 64, updatable = false)
    private String aiAnalysisId;

    @Column(name = "item_id", nullable = false, length = 128, updatable = false)
    private String itemId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feedback_body", nullable = false, updatable = false)
    private String feedbackBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AnalysisFeedbackStatus status;

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

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public static AnalysisFeedback pending(String aiAnalysisId, String itemId, String feedbackBody, LocalDateTime now, LocalDateTime expiresAt) {
        AnalysisFeedback analysisFeedback = new AnalysisFeedback();
        analysisFeedback.aiAnalysisId = aiAnalysisId;
        analysisFeedback.itemId = itemId;
        analysisFeedback.feedbackBody = feedbackBody;
        analysisFeedback.status = AnalysisFeedbackStatus.PENDING;
        analysisFeedback.attemptCount = 0;
        analysisFeedback.nextAttemptAt = now;
        analysisFeedback.expiresAt = expiresAt;
        return analysisFeedback;
    }
}
