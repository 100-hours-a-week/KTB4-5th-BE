package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyQuotaStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationDailyQuotaService {
    private static final int DAILY_LIMIT = 10;
    private final RecommendationDailyQuotaStore recommendationDailyQuotaStore;
    private final Clock clock;

    public RecommendationDailyGeneration current(Long userId) {
        LocalDate date = BusinessTime.today(clock);
        return snapshot(recommendationDailyQuotaStore.count(userId, date), date);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RecommendationDailyGeneration reserve(Long userId, String requestId) {
        LocalDateTime now = BusinessTime.now(clock);
        LocalDate date = now.toLocalDate();
        Duration ttl = Duration.between(now, date.plusDays(1).atStartOfDay());
        long used = recommendationDailyQuotaStore.reserve(userId, date, requestId, DAILY_LIMIT, ttl);
        if (used == RecommendationDailyQuotaStore.LIMIT_REACHED) {
            throw new CustomException(RecommendationExceptionCode.DAILY_LIMIT_REACHED);
        }
        if (used == RecommendationDailyQuotaStore.ALREADY_RESERVED) {
            return snapshot(recommendationDailyQuotaStore.count(userId, date), date);
        }
        releaseOnRollback(userId, date, requestId);
        return snapshot(used, date);
    }

    private void releaseOnRollback(Long userId, LocalDate date, String requestId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    recommendationDailyQuotaStore.release(userId, date, requestId);
                } catch (DataAccessException exception) {
                    log.warn("추천 한도 예약 반환 실패: userId={}, date={}, requestId={}", userId, date, requestId, exception);
                }
            }
        });
    }

    private RecommendationDailyGeneration snapshot(long used, LocalDate date) {
        return new RecommendationDailyGeneration(DAILY_LIMIT, Math.max(0, DAILY_LIMIT - Math.toIntExact(used)),
                date.plusDays(1).atStartOfDay(BusinessTime.ZONE).toOffsetDateTime());
    }
}
