package com.dameokja.backend.refrigerator.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "refrigerator.expiration-accounting.enabled", havingValue = "true", matchIfMissing = true)
public class ExpirationAccountingScheduler {
    private static final int BATCH_SIZE = 100;
    private final MonthlyExpirationService monthlyExpirationService;

    // 한국 시간 자정에 하루 한 번 집계한다. 알림 설정과 독립적이다.
    @Scheduled(cron = "${refrigerator.expiration-accounting.cron:0 0 0 * * *}", zone = "Asia/Seoul")
    public void accountExpirations() {
        long afterId = 0;
        List<Long> ids;
        do {
            ids = monthlyExpirationService.findPendingIds(afterId, BATCH_SIZE);
            for (Long id : ids) {
                synchronize(id);
                afterId = id;
            }
        } while (ids.size() == BATCH_SIZE);
    }

    private void synchronize(Long refrigeratorId) {
        try {
            monthlyExpirationService.synchronize(refrigeratorId);
        } catch (RuntimeException exception) {
            log.error("월별 만료 집계 실패. 다음 실행에서 재시도합니다. refrigeratorId={}", refrigeratorId, exception);
        }
    }
}
