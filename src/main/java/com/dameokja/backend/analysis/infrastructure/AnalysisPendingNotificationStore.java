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

// 결과를 저장했지만 알림을 다 보냈는지 아직 확인되지 않은 작업 목록이다. 결과 저장 때 올리고, 모두 보낸 뒤에 내린다.
@Repository
@RequiredArgsConstructor
public class AnalysisPendingNotificationStore {
    private static final String PENDING_KEY = "analysis:notification:pending";
    private static final String SSE_MISS_PREFIX = "analysis:notification:sse-miss:";
    // 알림을 끝내면 지운다. 서버가 그 사이 멈춰도 남지 않게만 둔다.
    private static final Duration SSE_MISS_TTL = Duration.ofDays(1);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM_DUE = RedisScript.of(new ClassPathResource("redis/analysis/claim-due-notifications.lua"), List.class);
    private final StringRedisTemplate redis;

    public void schedule(String analysisJobId, Instant nextCheckAt) {
        redis.opsForZSet().add(PENDING_KEY, analysisJobId, nextCheckAt.toEpochMilli());
    }

    @SuppressWarnings("unchecked")
    public List<String> claimDue(Instant now, Duration lease, int limit) {
        List<String> claimedJobIds = redis.execute(CLAIM_DUE, List.of(PENDING_KEY), Long.toString(now.toEpochMilli()),
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
        redis.opsForZSet().remove(PENDING_KEY, analysisJobId);
        redis.delete(SSE_MISS_PREFIX + analysisJobId);
    }
}
