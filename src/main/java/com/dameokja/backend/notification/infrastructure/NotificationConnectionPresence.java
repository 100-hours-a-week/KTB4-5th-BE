package com.dameokja.backend.notification.infrastructure;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationConnectionPresence {
    private static final String KEY_PREFIX = "fridgely:notifications:connections:";
    private static final String LEASE_MILLIS = "75000";
    private static final String PRUNE = """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            """;
    private static final RedisScript<Long> REFRESH = RedisScript.of(PRUNE + """
            redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private static final RedisScript<Long> CONNECTED = RedisScript.of(PRUNE + "return redis.call('ZCARD', KEYS[1])", Long.class);
    private final StringRedisTemplate redis;

    public boolean isConnected(Long userId) {
        Long count = redis.execute(CONNECTED, List.of(KEY_PREFIX + userId));
        return count != null && count > 0;
    }

    public void refresh(Long userId, String connectionId) {
        redis.execute(REFRESH, List.of(KEY_PREFIX + userId), connectionId, LEASE_MILLIS);
    }

    public void remove(Long userId, String connectionId) {
        redis.opsForZSet().remove(KEY_PREFIX + userId, connectionId);
    }
}
