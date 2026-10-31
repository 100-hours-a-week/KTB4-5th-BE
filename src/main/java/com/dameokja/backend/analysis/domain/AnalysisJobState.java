package com.dameokja.backend.analysis.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@DynamicUpdate
@Table(name = "analysis_job_states")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJobState extends BaseEntity {
    @Id
    @Column(name = "analysis_job_id", length = 36)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisStatus status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_body", nullable = false)
    private String analysisJobBody;
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    @Column(name = "notification_next_attempt_at")
    private LocalDateTime notificationNextAttemptAt;
    @Column(name = "notification_lease_token", length = 36)
    private String notificationLeaseToken;
    @Column(name = "notification_sse_attempt_count", nullable = false)
    private int notificationSseAttemptCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_image_attempt_counts")
    private Map<String, Integer> notificationImageAttemptCounts = new HashMap<>();
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public void updateResult(String analysisJobBody, AnalysisStatus status, LocalDateTime expiresAt) {
        this.analysisJobBody = analysisJobBody;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public void scheduleNotification(LocalDateTime nextAttemptAt) {
        if (notificationNextAttemptAt == null) { notificationNextAttemptAt = nextAttemptAt; }
    }

    public void leaseNotification(String leaseToken, LocalDateTime leaseUntil) {
        notificationLeaseToken = leaseToken;
        notificationNextAttemptAt = leaseUntil;
    }

    public int claimSseAttempt(int maximumAttempts) {
        if (notificationSseAttemptCount >= maximumAttempts) { return 0; }
        return ++notificationSseAttemptCount;
    }

    public Map<String, Integer> getNotificationImageAttemptCounts() {
        return notificationImageAttemptCounts == null ? Map.of() : Map.copyOf(notificationImageAttemptCounts);
    }

    public boolean claimImageEventAttempt(String requestId, int maximumAttempts) {
        int attemptCount = getNotificationImageAttemptCounts().getOrDefault(requestId, 0);
        if (attemptCount >= maximumAttempts) { return false; }
        Map<String, Integer> updatedAttemptCounts = new HashMap<>(getNotificationImageAttemptCounts());
        updatedAttemptCounts.put(requestId, attemptCount + 1);
        notificationImageAttemptCounts = updatedAttemptCounts;
        return true;
    }

    public void completeNotificationAttempt(LocalDateTime nextAttemptAt) {
        notificationLeaseToken = null;
        notificationNextAttemptAt = nextAttemptAt;
    }

    public static AnalysisJobState queued(User user, String analysisJobId, String analysisJobBody) {
        AnalysisJobState analysisJobState = new AnalysisJobState();
        analysisJobState.id = analysisJobId;
        analysisJobState.user = user;
        analysisJobState.status = AnalysisStatus.QUEUED;
        analysisJobState.analysisJobBody = analysisJobBody;
        return analysisJobState;
    }
}
