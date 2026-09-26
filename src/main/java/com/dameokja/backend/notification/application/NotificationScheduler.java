package com.dameokja.backend.notification.application;

import com.dameokja.backend.push.application.PushDispatchService;
import com.dameokja.backend.push.application.PushNotificationCreationService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private final Clock clock;
    private final boolean expirationPushEnabled;

    public NotificationScheduler(PushNotificationCreationService pushNotificationCreationService,
                                 PushDispatchService pushDispatchService, Clock clock,
                                 @Value("${push.expiration-batch.enabled:true}") boolean expirationPushEnabled) {
        this.pushNotificationCreationService = pushNotificationCreationService;
        this.pushDispatchService = pushDispatchService;
        this.clock = clock;
        this.expirationPushEnabled = expirationPushEnabled;
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    public void runExpirationNotificationBatch() {
        // TODO: 아래 흐름으로 인앱 알림 생성 서비스를 연결한다.
        // 1. 서비스 전체에서 알림 생성 대상 냉장고 목록을 조회한다.
        // 2. 냉장고별로 생성 서비스를 호출한다. 한 냉장고의 실패가 다른 냉장고에 영향을 주지 않도록 트랜잭션을 나눈다.
        //    - 처리 시점의 재고와 수신 대상을 조회하고 임박·만료 알림을 구성한다.
        //    - 알림과 수신자를 같은 트랜잭션에서 저장하고, 모두 성공하면 커밋한다.
        //    - 저장 중 실패하면 해당 냉장고의 알림·수신자 저장을 함께 롤백한다.
        // 3. 냉장고별 트랜잭션 호출 바깥에서 실패를 기록하고, 다음 냉장고 처리를 계속한다.
        // 4. 전체 냉장고 처리가 끝나면 아래 푸시 흐름을 호출한다.
        //    푸시는 DB에 커밋된 알림·수신자를 조회하므로, 이번 생성에서 롤백된 알림은 발송 작업으로 만들어지지 않는다.

        sendPushNotifications();
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void runNotificationCleanup() {
        // TODO: 사용자 × 냉장고별로 99개 초과분을 정리하는 서비스를 호출한다.
        // TODO: 읽은 알림 중 오래된 것부터 삭제하고, 부족하면 안 읽은 알림 중 오래된 것부터 삭제한다.
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
