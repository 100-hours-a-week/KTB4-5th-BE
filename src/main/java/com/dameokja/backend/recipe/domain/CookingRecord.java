package com.dameokja.backend.recipe.domain;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "cooking_records", uniqueConstraints = @UniqueConstraint(name = "uk_cooking_idempotency", columnNames = "idempotency_key"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CookingRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cooking_record_id")
    private Long id;
    @Column(nullable = false, length = 64, updatable = false)
    private String recommendationId;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "recipe_identity", columnDefinition = "TEXT", updatable = false)
    private String recipeId;
    @Column(length = 128, updatable = false)
    private String detailEtag;
    @Column(nullable = false, length = 255, updatable = false)
    private String recipeNameSnapshot;
    @Column(nullable = false, length = 64, updatable = false)
    private String idempotencyKey;
    @Column(nullable = false, updatable = false)
    private LocalDateTime cookedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refrigerator_id", nullable = false, updatable = false)
    private Refrigerator refrigerator;

    @Builder
    public CookingRecord(User user, Refrigerator refrigerator, String recommendationId, String recipeId, String recipeNameSnapshot,
            String idempotencyKey, String detailEtag, LocalDateTime cookedAt) {
        this.user = Objects.requireNonNull(user);
        this.refrigerator = Objects.requireNonNull(refrigerator);
        this.recommendationId = Objects.requireNonNull(recommendationId);
        this.recipeId = Objects.requireNonNull(recipeId);
        this.recipeNameSnapshot = Objects.requireNonNull(recipeNameSnapshot);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.detailEtag = Objects.requireNonNull(detailEtag);
        this.cookedAt = Objects.requireNonNull(cookedAt);
    }

    public boolean matchesRequest(Long userId, Long refrigeratorId, String recommendationId, String recipeId, String detailEtag) {
        return this.recipeId != null && this.detailEtag != null
                && user.getId().equals(userId) && refrigerator.getId().equals(refrigeratorId)
                && this.recommendationId.equals(recommendationId) && this.recipeId.equals(recipeId) && this.detailEtag.equals(detailEtag);
    }
}
