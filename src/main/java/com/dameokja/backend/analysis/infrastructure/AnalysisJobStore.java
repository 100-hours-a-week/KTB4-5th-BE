package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Repository
public class AnalysisJobStore {
    private static final String PREFIX = "analysis:job:";
    private static final int MAX_ATTEMPTS = 32;
    private static final RedisScript<Long> REPLACE = RedisScript.of(new ClassPathResource("redis/analysis/replace-analysis-job.lua"), Long.class);
    private final Clock clock;
    private final Duration duration;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AnalysisJobStore(Clock clock, @Value("${analysis.job-ttl}") Duration duration,
            StringRedisTemplate redis, ObjectMapper mapper) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("분석 작업 TTL은 양수여야 합니다.");
        }
        this.clock = clock;
        this.duration = duration;
        this.redis = redis;
        this.mapper = mapper;
    }

    public AnalysisJob create(Long userId, String inputHint, List<AnalysisImage> analysisImages) {
        OffsetDateTime submittedAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime();
        AnalysisJob job = new AnalysisJob(UUID.randomUUID().toString(), userId, inputHint, analysisImages, submittedAt, submittedAt.plus(duration));
        redis.opsForValue().set(PREFIX + job.id(), mapper.writeValueAsString(job), Duration.ofMillis(Math.max(1, duration.toMillis())));
        return job;
    }

    public AnalysisJob findOwned(String analysisJobId, Long userId) {
        return requireOwned(redis.opsForValue().get(PREFIX + analysisJobId), userId);
    }

    public void register(String analysisJobId, Long userId, String requestId, String analysisId) {
        String key = PREFIX + analysisJobId;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob updated = requireOwned(previous, userId).register(requestId, analysisId);
            // 다른 인스턴스의 등록 결과를 덮어쓰지 않고 최초 TTL을 유지한다.
            if (Long.valueOf(1).equals(redis.execute(REPLACE, List.of(key), previous, mapper.writeValueAsString(updated)))) {
                return;
            }
        }
        throw new ConcurrencyFailureException("분석 작업 등록이 동시 변경과 충돌했습니다.");
    }

    private AnalysisJob requireOwned(String stored, Long userId) {
        AnalysisJob job = stored == null ? null : mapper.readerFor(AnalysisJob.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(stored);
        if (job == null || !clock.instant().isBefore(job.expiresAt().toInstant())) {
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        if (!job.userId().equals(userId)) {
            throw new CustomException(GlobalExceptionCode.FORBIDDEN);
        }
        return job;
    }
}
