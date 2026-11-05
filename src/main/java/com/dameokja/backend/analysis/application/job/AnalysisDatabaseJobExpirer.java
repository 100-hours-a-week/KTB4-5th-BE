package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AnalysisDatabaseJobExpirer {
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final AnalysisJobStateRepository analysisJobStateRepository;
    private final Duration jobTimeout;
    private final Clock clock;
    private final Duration resultTtl;

    public AnalysisDatabaseJobExpirer(AnalysisJobStateAccess analysisJobStateAccess, AnalysisJobStateRepository analysisJobStateRepository, Clock clock,
            @Value("${analysis.job-timeout:PT30M30S}") Duration jobTimeout, @Value("${analysis.result-ttl:7d}") Duration resultTtl) {
        if (jobTimeout == null || jobTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("분석 작업 제한 시간은 1밀리초 이상이어야 합니다.");
        }
        if (resultTtl == null || resultTtl.toMillis() < 1) {
            throw new IllegalArgumentException("분석 결과 보관 TTL은 1밀리초 이상이어야 합니다.");
        }
        this.analysisJobStateAccess = analysisJobStateAccess;
        this.analysisJobStateRepository = analysisJobStateRepository;
        this.jobTimeout = jobTimeout;
        this.clock = clock;
        this.resultTtl = resultTtl;
    }

    @Transactional
    public AnalysisJob expireIfOverdue(AnalysisJob analysisJob) {
        if (!isOverdue(analysisJob)) {
            return analysisJob;
        }
        // 조회 뒤 완료 이벤트가 저장됐을 수 있으므로 잠금으로 읽은 최신 작업에서 다시 판정한다.
        return analysisJobStateAccess.update(analysisJob.id(), this::expireAndScheduleNotification);
    }

    private AnalysisJob expireAndScheduleNotification(AnalysisJob analysisJob) {
        if (!isOverdue(analysisJob)) {
            return analysisJob;
        }
        AnalysisJob expiredAnalysisJob = expireUnfinishedImages(analysisJob);
        analysisJobStateRepository.getReferenceById(analysisJob.id()).scheduleNotification(BusinessTime.now(clock));
        return expiredAnalysisJob;
    }

    private AnalysisJob expireUnfinishedImages(AnalysisJob analysisJob) {
        OffsetDateTime completedAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime();
        AnalysisCompletion failedCompletion = new AnalysisCompletion(AnalysisStatus.FAILED, completedAt, null, AnalysisFailureCode.ANALYSIS_EXPIRED.toFailure());
        AnalysisJob expiredAnalysisJob = analysisJob;
        for (AnalysisImage analysisImage : analysisJob.analysisImages()) {
            if (analysisImage.completion() == null) {
                expiredAnalysisJob = expiredAnalysisJob.fail(analysisImage.requestId(), failedCompletion, completedAt.plus(resultTtl));
            }
        }
        return expiredAnalysisJob;
    }

    private boolean isOverdue(AnalysisJob analysisJob) {
        return analysisJob.expiresAt() == null && !analysisJob.finished() && !clock.instant().isBefore(analysisJob.submittedAt().toInstant()
                .plus(jobTimeout));
    }
}
