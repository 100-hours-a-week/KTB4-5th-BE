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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
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
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static AnalysisJobState queued(User user, String analysisJobId, String analysisJobBody) {
        AnalysisJobState analysisJobState = new AnalysisJobState();
        analysisJobState.id = analysisJobId;
        analysisJobState.user = user;
        analysisJobState.status = AnalysisStatus.QUEUED;
        analysisJobState.analysisJobBody = analysisJobBody;
        return analysisJobState;
    }
}
