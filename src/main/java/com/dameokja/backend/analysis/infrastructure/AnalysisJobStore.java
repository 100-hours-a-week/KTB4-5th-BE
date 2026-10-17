package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
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
    private static final String USER_PREFIX = "analysis:user:";
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
        return create(UUID.randomUUID().toString(), userId, inputHint, analysisImages);
    }

    public AnalysisJob create(String id, Long userId, String inputHint, List<AnalysisImage> analysisImages) {
        OffsetDateTime submittedAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime();
        AnalysisJob job = new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, null, false);
        redis.opsForValue().set(PREFIX + job.id(), mapper.writeValueAsString(job));
        // 작업을 먼저 저장해야 목록 조회의 만료 정리가 생성 중인 작업을 색인에서 지우지 않는다.
        redis.opsForZSet().add(USER_PREFIX + userId, job.id(), submittedAt.toInstant().toEpochMilli());
        return job;
    }

    public List<AnalysisJob> findAllOwned(Long userId) {
        String key = USER_PREFIX + userId;
        List<String> ids = List.copyOf(Objects.requireNonNullElse(redis.opsForZSet().reverseRange(key, 0, -1), Set.<String>of()));
        if (ids.isEmpty()) { return List.of(); }
        List<String> stored = redis.opsForValue().multiGet(ids.stream().map(id -> PREFIX + id).toList());
        List<AnalysisJob> jobs = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            AnalysisJob job = readActive(stored.get(index));
            if (job == null) { stale.add(ids.get(index)); } else { jobs.add(job); }
        }
        if (!stale.isEmpty()) { redis.opsForZSet().remove(key, stale.toArray()); }
        return jobs;
    }

    public Optional<AnalysisJob> find(String analysisJobId) {
        return Optional.ofNullable(readActive(redis.opsForValue().get(PREFIX + analysisJobId)));
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

    public boolean isRegistered(String analysisId) {
        return Boolean.TRUE.equals(redis.hasKey(AI_PREFIX + analysisId));
    }

    public AnalysisJob findByAiAnalysisId(String analysisId) {
        String jobId = redis.opsForValue().get(AI_PREFIX + analysisId);
        return requireActive(jobId == null ? null : redis.opsForValue().get(PREFIX + jobId));
    }

    public AnalysisJob complete(String analysisId, AnalysisCompletion completion) {
        return finish(findByAiAnalysisId(analysisId).id(), (job, expiresAt) -> job.complete(analysisId, completion, expiresAt));
    }

    public AnalysisJob fail(String analysisJobId, String requestId, AnalysisCompletion failure) {
        return finish(analysisJobId, (job, expiresAt) -> job.fail(requestId, failure, expiresAt));
    }

    private AnalysisJob finish(String analysisJobId, BiFunction<AnalysisJob, OffsetDateTime, AnalysisJob> jobChange) {
        String key = PREFIX + analysisJobId;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            OffsetDateTime expiresAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime().plus(resultTtl);
            AnalysisJob updated = jobChange.apply(job, expiresAt);
            if (updated.equals(job)) {
                return job;
            }
            if (Long.valueOf(1).equals(replace(key, previous, updated))) {
                return updated;
            }
        }
        throw new ConcurrencyFailureException("분석 결과 저장이 동시 변경과 충돌했습니다.");
    }

    public AnalysisJob selectNotificationChannel(String id, AnalysisNotificationChannel channel) {
        String key = PREFIX + id;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            AnalysisJob notificationSelectedJob = job.selectNotificationChannel(channel);
            if (notificationSelectedJob == job || Long.valueOf(1).equals(replace(key, previous, notificationSelectedJob))) { return notificationSelectedJob; }
        }
        throw new ConcurrencyFailureException("분석 완료 알림 경로 선택이 동시 변경과 충돌했습니다.");
    }

    public AnalysisJob fallBackToPush(String id) {
        String key = PREFIX + id;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            AnalysisJob pushFallbackJob = job.fallBackToPush();
            if (pushFallbackJob == job || Long.valueOf(1).equals(replace(key, previous, pushFallbackJob))) { return pushFallbackJob; }
        }
        throw new ConcurrencyFailureException("분석 완료 알림 푸시 전환이 동시 변경과 충돌했습니다.");
    }

    // 선점에 성공한 호출만 이벤트를 보낸다. 보내지 못하면 releaseImageEvent로 되돌려 재시도가 다시 선점하게 한다.
    public boolean claimImageEvent(String id, String requestId) {
        String key = PREFIX + id;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            if (job.imageEventPublished(requestId)) { return false; }
            if (Long.valueOf(1).equals(replace(key, previous, job.publishImageEvent(requestId)))) { return true; }
        }
        throw new ConcurrencyFailureException("이미지 완료 이벤트 선점이 동시 변경과 충돌했습니다.");
    }

    public void releaseImageEvent(String id, String requestId) {
        String key = PREFIX + id;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            if (!job.imageEventPublished(requestId) || Long.valueOf(1).equals(replace(key, previous, job.withdrawImageEvent(requestId)))) { return; }
        }
        throw new ConcurrencyFailureException("이미지 완료 이벤트 선점 해제가 동시 변경과 충돌했습니다.");
    }

    // 알림 대기를 먼저 끝낸 호출만 완료 알림을 보낸다. 여러 서버가 같은 작업을 재처리해도 한 번만 나간다.
    public boolean acknowledgeNotification(String id) {
        String key = PREFIX + id;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            if (!job.notificationPending()) { return false; }
            if (Long.valueOf(1).equals(replace(key, previous, job.acknowledgeNotification()))) { return true; }
        }
        throw new ConcurrencyFailureException("분석 완료 알림 확인이 동시 변경과 충돌했습니다.");
    }

    // 완료 알림을 보내지 못하면 대기로 되돌려 재처리가 다시 보내게 한다.
    public void reopenNotification(String id) {
        String key = PREFIX + id;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            AnalysisJob job = requireActive(previous);
            if (job.notificationPending() || Long.valueOf(1).equals(replace(key, previous, job.reopenNotification()))) { return; }
        }
        throw new ConcurrencyFailureException("분석 완료 알림 대기 복원이 동시 변경과 충돌했습니다.");
    }

    public void delete(String analysisJobId) {
        String key = PREFIX + analysisJobId;
        String stored = redis.opsForValue().get(key);
        if (stored != null) {
            AnalysisJob job = requireActive(stored);
            redis.delete(keys(key, job));
            redis.opsForZSet().remove(USER_PREFIX + job.userId(), analysisJobId);
        }
    }

    private AnalysisJob requireOwned(String stored, Long userId) {
        AnalysisJob job = requireActive(stored);
        if (!job.userId().equals(userId)) {
            throw new CustomException(GlobalExceptionCode.FORBIDDEN);
        }
        return job;
    }

    private Long replace(String key, String previous, AnalysisJob updated) {
        String deadline = updated.expiresAt() == null ? "" : Long.toString(updated.expiresAt().toInstant().toEpochMilli());
        return redis.execute(REPLACE, keys(key, updated), previous, mapper.writeValueAsString(updated), updated.id(), deadline);
    }

    private List<String> keys(String key, AnalysisJob job) {
        return Stream.concat(Stream.of(key), job.analysisImages().stream()
                .filter(image -> image.aiAnalysisId() != null).map(image -> AI_PREFIX + image.aiAnalysisId())).toList();
    }

    private AnalysisJob requireActive(String stored) {
        AnalysisJob job = readActive(stored);
        if (job == null) {
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        return job;
    }

    private AnalysisJob readActive(String stored) {
        AnalysisJob job = stored == null ? null : mapper.readerFor(AnalysisJob.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(stored);
        if (job == null || (job.expiresAt() != null && !clock.instant().isBefore(job.expiresAt().toInstant()))) {
            return null;
        }
        return job;
    }
}
