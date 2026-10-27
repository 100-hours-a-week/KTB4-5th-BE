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
    public enum Status { PENDING, SENT, FAILED }

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

    public RecommendationOutbox(String eventId, Refrigerator refrigerator, String requestId, String payload) {
        this.eventId = eventId;
        this.refrigerator = refrigerator;
        this.requestId = requestId;
        this.eventType = "recipe.recommendation.requested.v1";
        this.payload = payload;
        this.status = Status.PENDING;
    }
}
