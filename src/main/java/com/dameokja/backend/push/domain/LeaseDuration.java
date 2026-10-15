package com.dameokja.backend.push.domain;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.push.exception.PushExceptionCode;
import java.time.Duration;
import java.time.LocalDateTime;

public record LeaseDuration(Duration value) {
    public LeaseDuration {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new CustomException(PushExceptionCode.LEASE_DURATION_INVALID);
        }
    }

    public LocalDateTime expiresAt(LocalDateTime startAt) {
        return startAt.plus(value);
    }
}
