package com.dameokja.backend.push.domain;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.push.exception.PushExceptionCode;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseDurationTest {
    @Test
    void calculatesExpiryFromTheProvidedStartTime() {
        LeaseDuration duration = new LeaseDuration(Duration.ofSeconds(30));
        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 8, 0);
        assertThat(duration.expiresAt(start)).isEqualTo(start.plusSeconds(30));
    }

    @Test
    void rejectsMissingAndNonPositiveDurations() {
        for (Duration invalid : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new LeaseDuration(invalid)).isInstanceOf(CustomException.class)
                    .extracting("exceptionCode").isEqualTo(PushExceptionCode.LEASE_DURATION_INVALID);
        }
    }
}
