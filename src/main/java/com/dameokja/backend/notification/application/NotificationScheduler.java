package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.infrastructure.ExpirationCheckRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
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
    private final RefrigeratorService refrigeratorService;
    private final ExpirationNotificationService expirationNotificationService;
    private final NotificationRecipientService notificationRecipientService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final boolean expirationPushEnabled;
    private final ExpirationCheckRepository expirationChecks;
    private final boolean expirationKafkaEnabled;

    public NotificationScheduler(RefrigeratorService refrigeratorService,
                                 ExpirationNotificationService expirationNotificationService,
                                 NotificationRecipientService notificationRecipientService,
                                 NotificationService notificationService, Clock clock,
                                 @Value("${push.expiration-batch.enabled:true}") boolean expirationPushEnabled,
                                 ExpirationCheckRepository expirationChecks,
                                 @Value("${notification.expiration-kafka.enabled:false}") boolean expirationKafkaEnabled) {
        this.refrigeratorService = refrigeratorService;
        this.expirationNotificationService = expirationNotificationService;
        this.notificationRecipientService = notificationRecipientService;
        this.notificationService = notificationService;
        this.clock = clock;
        this.expirationPushEnabled = expirationPushEnabled;
        this.expirationChecks = expirationChecks;
        this.expirationKafkaEnabled = expirationKafkaEnabled;
    }

    @Scheduled(cron = "${notification.expiration-batch.cron:0 0 8 * * *}", zone = "Asia/Seoul")
    public void runExpirationNotificationBatch() {
        long startedAtNanos = recordBatchStart();
        LocalDate businessDate = BusinessTime.today(clock);
        List<Long> refrigeratorIds = refrigeratorService.findNotificationTargetRefrigeratorIds();
        int failedRefrigeratorCount = processRefrigerators(refrigeratorIds, businessDate);
        recordGenerationResult(startedAtNanos, refrigeratorIds.size(), failedRefrigeratorCount);
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
    private int processRefrigerators(List<Long> refrigeratorIds, LocalDate businessDate) {
        int failedCount = 0;
        for (Long refrigeratorId : refrigeratorIds) {
            try {
                if (expirationKafkaEnabled) {
                    expirationChecks.reserve(businessDate, refrigeratorId);
                } else {
                    expirationNotificationService.generate(refrigeratorId, expirationPushEnabled);
                }
            } catch (RuntimeException exception) {
                failedCount++;
                log.error("냉장고 알림 배치 처리에 실패했습니다. refrigeratorId={}", refrigeratorId, exception);
            }
        }
        return failedCount;
    }

    private void recordGenerationResult(long startedAtNanos, int refrigeratorCount, int failedRefrigeratorCount) {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
        log.info("임박·만료 알림 배치를 마쳤습니다. refrigeratorCount={}, failedRefrigeratorCount={}, elapsedMillis={}",
                refrigeratorCount, failedRefrigeratorCount, elapsedMillis);
    }

    private LocalDateTime now() {
        return BusinessTime.now(clock);
    }
}
