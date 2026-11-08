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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recommendation_daily_jobs", uniqueConstraints =
        @UniqueConstraint(name = "uk_recommendation_daily_participant", columnNames = {"business_date", "user_id", "refrigerator_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationDailyJob extends BaseEntity {
    public enum Status { PENDING, CLAIMED, ACCEPTED, REUSED, SKIPPED, COMPLETED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommendation_daily_job_id")
    private Long id;
    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refrigerator_id", nullable = false, updatable = false)
    private Refrigerator refrigerator;
    @Column(nullable = false, unique = true, length = 36, updatable = false)
    private String requestId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_list_id")
    private RecommendationList recommendationList;
    @Column(length = 64)
    private String reason;
    @Column(length = 36)
    private String leaseToken;
    private LocalDateTime leaseUntil;

    public void claim(String token, LocalDateTime until) {
        status = Status.CLAIMED;
        leaseToken = token;
        leaseUntil = until;
    }

    public void skip(String reason) {
        this.reason = Objects.requireNonNull(reason);
        status = Status.SKIPPED;
        leaseToken = null;
        leaseUntil = null;
    }

    public void markAccepted() {
        status = Status.ACCEPTED;
        clearLease();
    }

    public void reuse(RecommendationList list) {
        recommendationList = Objects.requireNonNull(list);
        status = Status.REUSED;
        clearLease();
    }

    public void complete(RecommendationList list) {
        recommendationList = Objects.requireNonNull(list);
        status = Status.COMPLETED;
    }

    public void fail(String reason) {
        this.reason = Objects.requireNonNull(reason);
        status = Status.FAILED;
    }

    private void clearLease() {
        leaseToken = null;
        leaseUntil = null;
    }
}
