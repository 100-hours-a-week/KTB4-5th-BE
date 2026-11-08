package com.dameokja.backend.recipe.infrastructure;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecommendationDailyQuotaStore {
    public static final long LIMIT_REACHED = -1;
    public static final long ALREADY_RESERVED = 0;
    private static final RedisScript<Long> RESERVE = RedisScript.of(new ClassPathResource("redis/recipe/reserve-quota.lua"), Long.class);
    private final StringRedisTemplate redisTemplate;

    public long reserve(Long userId, LocalDate date, String requestId, int limit, Duration ttl) {
        return redisTemplate.execute(RESERVE, List.of(key(userId, date)), requestId, Integer.toString(limit), Long.toString(Math.max(1, ttl.toMillis())));
    }

    public long count(Long userId, LocalDate date) {
        return redisTemplate.opsForSet().size(key(userId, date));
    }

    public void release(Long userId, LocalDate date, String requestId) {
        redisTemplate.opsForSet().remove(key(userId, date), requestId);
    }

    private String key(Long userId, LocalDate date) { return "recipe:quota:" + userId + ":" + date; }
}
