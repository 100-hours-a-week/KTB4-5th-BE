package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
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
import java.util.stream.Stream;
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
    private static final String AI_PREFIX = "analysis:ai:";
    private static final int MAX_ATTEMPTS = 3;
    private static final RedisScript<Long> REPLACE = RedisScript.of(new ClassPathResource("redis/analysis/replace-analysis-job.lua"), Long.class);
    private final Clock clock;
    private final Duration resultTtl;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AnalysisJobStore(Clock clock, @Value("${analysis.result-ttl:7d}") Duration resultTtl,
            StringRedisTemplate redis, ObjectMapper mapper) {
        if (resultTtl == null || resultTtl.toMillis() < 1) {
            throw new IllegalArgumentException("분석 결과 보관 TTL은 1밀리초 이상이어야 합니다.");
        }
        this.clock = clock;
        this.resultTtl = resultTtl;
        this.redis = redis;
        this.mapper = mapper;
    }

    public AnalysisJob create(Long userId, String inputHint, List<AnalysisImage> analysisImages) {
        OffsetDateTime submittedAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime();
        AnalysisJob job = new AnalysisJob(UUID.randomUUID().toString(), userId, inputHint, analysisImages, submittedAt, null, false);
        redis.opsForValue().set(PREFIX + job.id(), mapper.writeValueAsString(job));
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
            Long replaced = replace(key, previous, updated);
            if (Long.valueOf(-1).equals(replaced)) {
                throw new IllegalStateException("다른 작업에 접수된 AI 작업 ID입니다.");
            }
            if (Long.valueOf(1).equals(replaced)) {
                return;
            }
        }
        throw new ConcurrencyFailureException("분석 작업 등록이 동시 변경과 충돌했습니다.");
    }

    public AnalysisJob findByAiAnalysisId(String analysisId) {
        String jobId = redis.opsForValue().get(AI_PREFIX + analysisId);
        return requireActive(jobId == null ? null : redis.opsForValue().get(PREFIX + jobId));
    }

    public AnalysisJob complete(String analysisId, AnalysisCompletion completion) {
        String key = PREFIX + findByAiAnalysisId(analysisId).id();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            OffsetDateTime expiresAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime().plus(resultTtl);
            AnalysisJob updated = job.complete(analysisId, completion, expiresAt);
            if (updated.equals(job)) {
                return job;
            }
            if (Long.valueOf(1).equals(replace(key, previous, updated))) {
                return updated;
            }
        }
        throw new ConcurrencyFailureException("분석 결과 저장이 동시 변경과 충돌했습니다.");
    }

    public void acknowledgeNotification(String id) {
        String key = PREFIX + id;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            if (Long.valueOf(1).equals(replace(key, previous, job.acknowledgeNotification()))) { return; }
        }
        throw new ConcurrencyFailureException("분석 완료 알림 확인이 동시 변경과 충돌했습니다.");
    }

    private AnalysisJob requireOwned(String stored, Long userId) {
        AnalysisJob job = requireActive(stored);
        if (!job.userId().equals(userId)) {
            throw new CustomException(GlobalExceptionCode.FORBIDDEN);
        }
        return job;
    }

    private Long replace(String key, String previous, AnalysisJob updated) {
        List<String> keys = Stream.concat(Stream.of(key), updated.analysisImages().stream()
                .filter(image -> image.aiAnalysisId() != null).map(image -> AI_PREFIX + image.aiAnalysisId())).toList();
        String deadline = updated.expiresAt() == null ? "" : Long.toString(updated.expiresAt().toInstant().toEpochMilli());
        return redis.execute(REPLACE, keys, previous, mapper.writeValueAsString(updated), updated.id(), deadline);
    }

    private AnalysisJob requireActive(String stored) {
        AnalysisJob job = stored == null ? null : mapper.readerFor(AnalysisJob.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(stored);
        if (job == null || (job.expiresAt() != null && !clock.instant().isBefore(job.expiresAt().toInstant()))) {
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        return job;
    }
}
