package com.dameokja.backend.global.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

import static org.assertj.core.api.Assertions.assertThat;

class JpaAuditingConfigTest {
    @Test
    void providesSeoulDateTimeTruncatedToMicrosRegardlessOfClockZone() {
        // UTC 2026-09-24 23:00은 서울 2026-09-25 08:00이다.
        Clock utcClock = Clock.fixed(Instant.parse("2026-09-24T23:00:00.123456789Z"), ZoneOffset.UTC);
        DateTimeProvider provider = new JpaAuditingConfig().auditingDateTimeProvider(utcClock);

        assertThat(provider.getNow()).contains(LocalDateTime.of(2026, 9, 25, 8, 0, 0, 123_456_000));
    }
}
