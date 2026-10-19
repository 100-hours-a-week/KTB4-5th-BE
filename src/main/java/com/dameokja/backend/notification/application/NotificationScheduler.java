package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.application.PushNotificationCreationService;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 배치는 작업 생성까지만 맡고 발송·재시도는 DB 워커가 독립적으로 처리한다.
@Slf4j
@Component
public class NotificationScheduler {
    private final PushNotificationCreationService pushNotificationCreationService;
    private final RefrigeratorService refrigeratorService;
    private final ExpirationNotificationService expirationNotificationService;
    private final NotificationRecipientService notificationRecipientService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final boolean expirationPushEnabled;

    public NotificationScheduler(PushNotificationCreationService pushNotificationCreationService,
                                 RefrigeratorService refrigeratorService,
                                 ExpirationNotificationService expirationNotificationService,
                                 NotificationRecipientService notificationRecipientService,
                                 NotificationService notificationService, Clock clock,
                                 @Value("${push.expiration-batch.enabled:true}") boolean expirationPushEnabled) {
        this.pushNotificationCreationService = pushNotificationCreationService;
        this.refrigeratorService = refrigeratorService;
        this.expirationNotificationService = expirationNotificationService;
        this.notificationRecipientService = notificationRecipientService;
        this.notificationService = notificationService;
        this.clock = clock;
        this.expirationPushEnabled = expirationPushEnabled;
    }

    @Scheduled(cron = "${notification.expiration-batch.cron:0 0 8 * * *}", zone = "Asia/Seoul")
    public void runExpirationNotificationBatch() {
        long startedAtNanos = recordBatchStart();
        List<Long> refrigeratorIds = refrigeratorService.findNotificationTargetRefrigeratorIds();
        int failedRefrigeratorCount = generateNotifications(refrigeratorIds);
        recordGenerationResult(startedAtNanos, refrigeratorIds.size(), failedRefrigeratorCount);
        createPushJobs();
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void runNotificationCleanup() {
        int deletedRecipients = notificationRecipientService.deleteExcessRecipients();
        int deletedNotifications = notificationService.deleteUnreferencedNotifications();

        log.info("알림 보관 한도 정리를 마쳤습니다. deletedRecipients={}, deletedNotifications={}",
                deletedRecipients, deletedNotifications);
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

    private void createPushJobs() {
        // 푸시를 꺼도 인앱 알림 생성과 별도 보관 정리는 실행되어야 한다.
        if (!expirationPushEnabled) {
            return;
        }
        int createdCount = pushNotificationCreationService.createExpirationJobs();
        log.info("만료·임박 알림 푸시 발송 작업을 {}건 만들었습니다.", createdCount);
    }

    private LocalDateTime now() {
        return BusinessTime.now(clock);
    }
}
