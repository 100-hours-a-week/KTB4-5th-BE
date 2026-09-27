package com.dameokja.backend.auth.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import org.springframework.test.util.ReflectionTestUtils;

final class RefreshStoreAssertions {
    private RefreshStoreAssertions() {}

    @SuppressWarnings("unchecked")
    static UserRefreshSessions snapshot(CaffeineRefreshSessionStore refreshSessionStore,
            Long userId) {
        Cache<Long, UserRefreshSessions> cache = (Cache<Long, UserRefreshSessions>)
                ReflectionTestUtils.getField(refreshSessionStore, "userSessionsCache");
        Clock clock = (Clock) ReflectionTestUtils.getField(refreshSessionStore, "clock");
        UserRefreshSessions current = cache.getIfPresent(userId);
        UserRefreshSessions copy = current == null
                ? new UserRefreshSessions() : new UserRefreshSessions(current);
        copy.removeExpired(clock.instant());
        return copy;
    }
}
