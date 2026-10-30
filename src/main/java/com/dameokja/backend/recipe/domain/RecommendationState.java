package com.dameokja.backend.recipe.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recommendation_states", uniqueConstraints =
        @UniqueConstraint(name = "uk_recommendation_state_participant", columnNames = {"user_id", "refrigerator_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationState extends BaseEntity {
    public enum GenerationStatus { QUEUED, PROCESSING, COMPLETED, FAILED }
    public enum GenerationTrigger { USER, SCHEDULED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommendation_state_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refrigerator_id", nullable = false, updatable = false)
    private Refrigerator refrigerator;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_list_id")
    private RecommendationList currentList;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "latest_list_id")
    private RecommendationList latestList;
    @Column(length = 36)
    private String activeRequestId;
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private GenerationStatus generationStatus;
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private GenerationTrigger generationTrigger;

    public RecommendationState(User user, Refrigerator refrigerator) {
        this.user = user;
        this.refrigerator = refrigerator;
    }
}
