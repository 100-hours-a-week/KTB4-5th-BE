package com.dameokja.backend.notification.application;

import com.dameokja.backend.push.application.PushDispatchService;
import com.dameokja.backend.push.application.PushNotificationCreationService;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 재시도 전용 배치를 두지 않고, 8시 실행 안에서 재시도 시각까지 기다렸다가 다시 보낸다.
// 기다리는 동안 스케줄러 스레드(기본 1개)를 점유하므로 다른 @Scheduled 작업이 생기면 스레드 수를 늘린다.
@Slf4j
@Component
public class NotificationScheduler {
    private final PushNotificationCreationService pushNotificationCreationService;
    private final PushDispatchService pushDispatchService;
    private final RefrigeratorService refrigeratorService;
    private final ExpirationNotificationService expirationNotificationService;
    private final Clock clock;
    private final boolean expirationPushEnabled;

    public NotificationScheduler(PushNotificationCreationService pushNotificationCreationService,
                                 PushDispatchService pushDispatchService,
                                 RefrigeratorService refrigeratorService,
                                 ExpirationNotificationService expirationNotificationService, Clock clock,
                                 @Value("${push.expiration-batch.enabled:true}") boolean expirationPushEnabled) {
        this.pushNotificationCreationService = pushNotificationCreationService;
        this.pushDispatchService = pushDispatchService;
        this.refrigeratorService = refrigeratorService;
        this.expirationNotificationService = expirationNotificationService;
        this.clock = clock;
        this.expirationPushEnabled = expirationPushEnabled;
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    public void runExpirationNotificationBatch() {
        long startedAtNanos = recordBatchStart();
        List<Long> refrigeratorIds = refrigeratorService.findNotificationTargetRefrigeratorIds();
        int failedRefrigeratorCount = generateNotifications(refrigeratorIds);
        recordGenerationResult(startedAtNanos, refrigeratorIds.size(), failedRefrigeratorCount);
        sendPushNotifications();
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void runNotificationCleanup() {
        // TODO: 사용자 × 냉장고별로 99개 초과분을 정리하는 서비스를 호출한다.
        // TODO: 읽은 알림 중 오래된 것부터 삭제하고, 부족하면 안 읽은 알림 중 오래된 것부터 삭제한다.
    }

    private long recordBatchStart() {
        long startedAtNanos = System.nanoTime();
        log.info("임박·만료 알림 배치를 시작합니다. startedAt={}", now());
        return startedAtNanos;
    }

    //todo: 로깅을 위한 try-catch. 추후 삭제 예정
    private int generateNotifications(List<Long> refrigeratorIds) {
        int failedCount = 0;
        for (Long refrigeratorId : refrigeratorIds) {
            try {
                expirationNotificationService.generate(refrigeratorId);
            } catch (RuntimeException exception) {
                failedCount++;
                log.error("냉장고 알림 생성에 실패했습니다. refrigeratorId={}", refrigeratorId, exception);
            }
        }
        return failedCount;
    }

    private void recordGenerationResult(long startedAtNanos, int refrigeratorCount, int failedRefrigeratorCount) {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
        log.info("임박·만료 알림 생성 단계를 마쳤습니다. refrigeratorCount={}, failedRefrigeratorCount={}, elapsedMillis={}",
                refrigeratorCount, failedRefrigeratorCount, elapsedMillis);
    }

    private void sendPushNotifications() {
        // 푸시를 꺼도 인앱 알림 생성과 별도 보관 정리는 실행되어야 한다.
        if (!expirationPushEnabled) {
            return;
        }
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
