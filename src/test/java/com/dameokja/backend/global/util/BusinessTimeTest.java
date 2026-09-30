package com.dameokja.backend.global.util;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeTest {
    // UTC 2026-09-24 23:00은 서울 2026-09-25 08:00이다.
    private final Clock utcClock = Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsSeoulDateTimeRegardlessOfClockZone() {
        assertThat(BusinessTime.now(utcClock)).isEqualTo(LocalDateTime.of(2026, 9, 25, 8, 0));
    }

    @Test
    void createsSeoulDateRegardlessOfClockZone() {
        assertThat(BusinessTime.today(utcClock)).isEqualTo(LocalDate.of(2026, 9, 25));
    }
}
