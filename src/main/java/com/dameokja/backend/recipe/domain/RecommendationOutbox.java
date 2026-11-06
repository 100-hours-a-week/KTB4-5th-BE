package com.dameokja.backend.recipe.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
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
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "recommendation_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationOutbox extends BaseEntity {
    // SENT는 메시지 전달 완료이며 추천 생성 완료 상태가 아니다.
    public enum Status { PENDING, PUBLISHING, SENT, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommendation_outbox_id")
    private Long id;
    @Column(nullable = false, unique = true, length = 36, updatable = false)
    private String eventId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refrigerator_id", nullable = false, updatable = false)
    private Refrigerator refrigerator;
    @Column(nullable = false, length = 36, updatable = false)
    private String requestId;
    @Column(nullable = false, length = 64)
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    @Column(length = 249, updatable = false)
    private String topic;
    @Column(length = 32, updatable = false)
    private String messageKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String messageBody;
    private LocalDateTime nextAttemptAt;
    @Column(length = 36)
    private String leaseToken;
    private LocalDateTime leaseUntil;

    @Column(nullable = false)
    private boolean dbJob;
    @JdbcTypeCode(SqlTypes.JSON)
    private String jobSnapshot;
    private LocalDateTime jobExpiresAt;

    public void saveJob(String snapshot, LocalDateTime expiresAt) {
        this.jobSnapshot = snapshot;
        this.jobExpiresAt = expiresAt;
    }

    public RecommendationOutbox(String eventId, Refrigerator refrigerator, String requestId, String payload, boolean dbJob) {
        this.dbJob = dbJob;
        this.eventId = eventId;
        this.refrigerator = refrigerator;
        this.requestId = requestId;
        this.eventType = "recipe.recommendation.requested.v1";
        this.payload = payload;
        this.status = Status.PENDING;
    }

    public void markSent() { this.status = Status.SENT; }

    public void prepareKafka(String topic, String messageKey, String messageBody, LocalDateTime nextAttemptAt) {
        if (id != null || this.messageBody != null) {
            throw new IllegalStateException("저장한 메시지는 변경할 수 없습니다.");
        }
        this.topic = Objects.requireNonNull(topic);
        this.messageKey = Objects.requireNonNull(messageKey);
        this.messageBody = Objects.requireNonNull(messageBody);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
    }

    public void lease(String token, LocalDateTime until) {
        status = Status.PUBLISHING;
        leaseToken = token;
        leaseUntil = until;
    }
}
