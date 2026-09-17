package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service
@RequiredArgsConstructor
@Transactional
public class ExpiredCountService {
    private final RefrigeratorRepository refrigerators;
    private final RefrigeratorAccessService access;
    private final Clock clock;

    public int getExpiredCount(Long userId, Long refrigeratorId) {
        Refrigerator refrigerator = locked(refrigeratorId);
        access.validateReadAccess(userId, refrigeratorId);
        return refrigerator.currentExpiredCount(month());
    }

    public int increaseExpiredCount(Long userId, Long refrigeratorId, int increment) {
        if (increment <= 0) throw new RefrigeratorException(RefrigeratorExceptionCode.INVALID_INCREMENT);
        Refrigerator refrigerator = locked(refrigeratorId);
        access.validateWriteAccess(userId, refrigeratorId);
        return refrigerator.increaseExpiredCount(month(), increment);
    }

    private Refrigerator locked(Long id) {
        return refrigerators.findByIdForUpdate(id)
                .orElseThrow(() -> new RefrigeratorException(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND));
    }

    private String month() { return YearMonth.from(clock.instant().atZone(ZoneId.of("Asia/Seoul"))).toString(); }
}
