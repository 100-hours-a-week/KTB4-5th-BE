package com.dameokja.backend.push.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 재시도 전용 배치를 두지 않고, 8시 실행 안에서 재시도 시각까지 기다렸다가 다시 보낸다.
// 기다리는 동안 스케줄러 스레드(기본 1개)를 점유하므로 다른 @Scheduled 작업이 생기면 스레드 수를 늘린다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "push.expiration-batch.enabled", havingValue = "true", matchIfMissing = true)
public class ExpirationPushScheduler {
    private final PushNotificationCreationService pushNotificationCreationService;
    private final PushDispatchService pushDispatchService;
    private final Clock clock;

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    public void run() {
        int createdCount = pushNotificationCreationService.createExpirationJobs();
        log.info("만료·임박 알림 푸시 발송 작업을 {}건 만들었습니다.", createdCount);
        LocalDateTime deadline = now().toLocalDate().atTime(PushNotificationCreationService.SEND_DEADLINE);
        Optional<LocalDateTime> nextAttemptAt = pushDispatchService.dispatchDueJobs();
        // 상태 저장이 계속 실패해 같은 작업이 남더라도 기한이 지나면 멈춘다.
        while (nextAttemptAt.isPresent() && now().isBefore(deadline)) {
            if (!waitUntil(nextAttemptAt.get())) {
                return;
            }
            nextAttemptAt = pushDispatchService.dispatchDueJobs();
        }
    }

    private boolean waitUntil(LocalDateTime nextAttemptAt) {
        Duration waitTime = Duration.between(now(), nextAttemptAt);
        if (waitTime.isNegative() || waitTime.isZero()) {
            return true;
        }
        try {
            Thread.sleep(waitTime);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(PushNotificationCreationService.BUSINESS_ZONE));
    }
}
