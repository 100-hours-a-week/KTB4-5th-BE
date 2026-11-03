package com.dameokja.backend.analysis.domain;

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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Getter;

@Getter
@Entity
@Table(name = "analysis_job_requests", uniqueConstraints = @UniqueConstraint(columnNames = {"request_id", "analysis_job_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJobRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_job_request_id")
    private Long id;
    @Column(name = "request_id", nullable = false, updatable = false, length = 36)
    private String requestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_job_id", nullable = false, updatable = false)
    private AnalysisJobState analysisJobState;

    public AnalysisJobRequest(AnalysisJobState analysisJobState, String requestId) {
        this.analysisJobState = analysisJobState;
        this.requestId = requestId;
    }
}
