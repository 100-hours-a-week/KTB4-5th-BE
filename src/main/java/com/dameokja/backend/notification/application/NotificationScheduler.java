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
        // TODO: 실행 시작 기록 → 대상 냉장고 조회 → 냉장고별 알림 생성 → 처리 결과 기록 → 푸시 호출 순서로 연결한다.

        // 1. 실행 시작 기록 [NotificationScheduler]
        // 실행 시작 시각을 기록한다.

        // 2. 대상 냉장고 조회 [RefrigeratorService]
        // 삭제되지 않은 알림 대상 냉장고의 ID 목록을 중복 없이 조회한다.
        // 조회 실패 시 스케줄러에서 실행 실패를 기록하고 종료한다. 대상이 없으면 생성 단계를 건너뛴다.

        // 3. 냉장고별 알림 생성 [NotificationScheduler → ExpirationNotificationService]
        // 스케줄러의 private 메서드에서 for문으로 냉장고별 생성 서비스를 순차 호출한다.
        // 생성 서비스는 처리 시점의 재고·수신 대상을 조회하고 Asia/Seoul 날짜로 임박(D-3~D-0)·만료(D+1~)를 분류한다.
        // 유형별 알림을 최대 1건씩 구성한다. 해당 유형의 재료나 수신 대상이 없으면 생성하지 않는다.
        // 알림·수신자를 냉장고별 트랜잭션에서 함께 저장한다. 성공하면 커밋하고, 실패하면 함께 롤백하고 예외를 전파한다.
        // 스케줄러의 private 메서드에서 냉장고 한 개의 호출 바깥으로 전파된 예외를 잡아 실패를 기록한다.
        // 실패한 냉장고는 재시도하지 않고 다음 냉장고로 진행한다.

        // 4. 처리 결과 기록 [NotificationScheduler]
        // 생성 단계의 실행 시간·처리 냉장고 수·실패 건수를 기록한다.

        // 5. 푸시 호출 [구독]
        // 기존 구독 기반 푸시 발송 흐름을 호출한다.
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
