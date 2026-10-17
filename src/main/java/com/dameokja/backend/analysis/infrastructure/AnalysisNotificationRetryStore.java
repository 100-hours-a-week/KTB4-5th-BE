package com.dameokja.backend.analysis.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalysisNotificationRetryStore {
    private static final String DUE_KEY = "analysis:notification:retry";
    private static final String SSE_MISS_PREFIX = "analysis:notification:sse-miss:";
    // 알림을 끝내면 지운다. 서버가 그 사이 멈춰도 남지 않게만 둔다.
    private static final Duration SSE_MISS_TTL = Duration.ofDays(1);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM_DUE = RedisScript.of(new ClassPathResource("redis/analysis/claim-due-notifications.lua"), List.class);
    private final StringRedisTemplate redis;

    public void schedule(String analysisJobId, Instant retryAt) {
        redis.opsForZSet().add(DUE_KEY, analysisJobId, retryAt.toEpochMilli());
    }

    @SuppressWarnings("unchecked")
    public List<String> claimDue(Instant now, Duration lease, int limit) {
        List<String> claimedJobIds = redis.execute(CLAIM_DUE, List.of(DUE_KEY), Long.toString(now.toEpochMilli()),
                Long.toString(now.plus(lease).toEpochMilli()), Integer.toString(limit));
        return Objects.requireNonNullElse(claimedJobIds, List.of());
    }

    public int recordSseMiss(String analysisJobId) {
        String key = SSE_MISS_PREFIX + analysisJobId;
        Long sseMissCount = redis.opsForValue().increment(key);
        redis.expire(key, SSE_MISS_TTL);
        return sseMissCount == null ? 0 : sseMissCount.intValue();
    }

    public void finish(String analysisJobId) {
        redis.opsForZSet().remove(DUE_KEY, analysisJobId);
        redis.delete(SSE_MISS_PREFIX + analysisJobId);
    }
}
