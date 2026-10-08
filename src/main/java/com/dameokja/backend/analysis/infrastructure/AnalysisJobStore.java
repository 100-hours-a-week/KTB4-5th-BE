package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisJobStore {
    private final Clock clock;
    private final Duration duration;
    private final Cache<String, AnalysisJob> cache;

    public AnalysisJobStore(Clock clock, @Value("${analysis.job-ttl}") Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("분석 작업 TTL은 양수여야 합니다.");
        }
        this.clock = clock;
        this.duration = duration;
        cache = Caffeine.newBuilder().expireAfterWrite(duration).build();
    }

    public AnalysisJob create(Long userId, String inputHint, List<AnalysisImage> analysisImages) {
        OffsetDateTime offsetDateTime = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime();
        AnalysisJob analysisJob = new AnalysisJob(UUID.randomUUID().toString(), userId, inputHint, analysisImages, offsetDateTime, offsetDateTime.plus(duration));
        cache.put(analysisJob.id(), analysisJob);
        return analysisJob;
    }

    public AnalysisJob findOwned(String analysisJobId, Long userId) {
        AnalysisJob analysisJob = cache.getIfPresent(analysisJobId);
        if (analysisJob != null && !clock.instant().isBefore(analysisJob.expiresAt().toInstant())) {
            cache.asMap().remove(analysisJobId, analysisJob);
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        return requireOwned(analysisJob, userId);
    }

    public void register(String analysisJobId, Long userId, String requestId, String analysisId) {
        cache.asMap().compute(analysisJobId, (key, analysisJob) -> requireOwned(analysisJob, userId).register(requestId, analysisId));
    }

    private AnalysisJob requireOwned(AnalysisJob analysisJob, Long userId) {
        if (analysisJob == null || !clock.instant().isBefore(analysisJob.expiresAt().toInstant())) {
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        if (!analysisJob.userId().equals(userId)) {
            throw new CustomException(GlobalExceptionCode.FORBIDDEN);
        }
        return analysisJob;
    }
}
