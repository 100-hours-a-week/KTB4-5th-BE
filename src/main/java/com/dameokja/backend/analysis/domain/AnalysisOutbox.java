package com.dameokja.backend.analysis.domain;

import com.dameokja.backend.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "analysis_outbox", uniqueConstraints = @UniqueConstraint(name = "uk_analysis_outbox_message", columnNames = "message_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisOutbox extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_outbox_id")
    private Long id;
    @Column(name = "message_id", nullable = false, length = 36, updatable = false)
    private String messageId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_job_id", nullable = false, updatable = false)
    private AnalysisJobState analysisJobState;
    @Column(name = "topic", nullable = false, length = 249, updatable = false)
    private String topic;
    @Column(name = "message_key", nullable = false, length = 32, updatable = false)
    private String messageKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_body", nullable = false, updatable = false)
    private String messageBody;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AnalysisOutboxStatus status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;
    @Column(name = "lease_token", length = 36)
    private String leaseToken;
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    public void lease(String leaseToken, LocalDateTime leaseUntil) {
        status = AnalysisOutboxStatus.PUBLISHING;
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
        attemptCount++;
    }

    public static AnalysisOutbox pending(AnalysisJobState analysisJobState, String messageId, String topic, String messageBody, LocalDateTime nextAttemptAt) {
        AnalysisOutbox analysisOutbox = new AnalysisOutbox();
        analysisOutbox.analysisJobState = analysisJobState;
        analysisOutbox.messageId = messageId;
        analysisOutbox.topic = topic;
        analysisOutbox.messageKey = analysisJobState.getUser().getId().toString();
        analysisOutbox.messageBody = messageBody;
        analysisOutbox.status = AnalysisOutboxStatus.PENDING;
        analysisOutbox.nextAttemptAt = nextAttemptAt;
        return analysisOutbox;
    }
}
