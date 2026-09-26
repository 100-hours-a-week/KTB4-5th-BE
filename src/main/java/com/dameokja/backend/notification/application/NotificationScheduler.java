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
        // TODO: 인앱 알림 생성 서비스 호출을 연결한다.
        // 호출 예정: expirationNotificationBatchService.generateNotifications();
        //
        // 1. 실행 시작 시각을 기록하고, 알림 생성 대상 냉장고의 ID 목록을 조회한다.
        //    - 조회 실패 시 실행 실패를 기록하고 예외를 전파해 종료한다.
        //    - 대상이 0개면 반복을 건너뛰고 푸시 흐름으로 진행한다.
        // 2. for (Long refrigeratorId : refrigeratorIds)로 냉장고별 생성 서비스를 순차 호출한다.
        //    - 냉장고별 생성 서비스의 @Transactional 메서드를 호출한다.
        //    - 처리 시점의 재고와 수신 대상을 조회하고, Asia/Seoul 날짜로 임박·만료를 분류한다.
        //    - 임박(D-3~D-0)과 만료(D+1~) 재료를 각각 묶어 냉장고당 유형별 알림을 최대 1건씩 구성한다.
        //    - 해당 유형의 재료나 수신 대상이 없으면 알림을 생성하지 않는다.
        //    - 알림과 수신자를 같은 트랜잭션에서 저장한다. 성공하면 커밋하고, 실패하면 함께 롤백하고 예외를 전파한다.
        // 3. 냉장고별 호출 결과를 집계한다.
        //    - 예외는 냉장고 한 개의 트랜잭션 호출 바깥에서 catch해 냉장고 ID·원인을 기록하고 실패 건수를 늘린다.
        //    - 실패한 냉장고는 재시도하지 않고 다음 냉장고로 넘어간다.
        // 4. 반복 종료 후 생성 단계의 실행 시간·처리 냉장고 수·실패 건수를 기록한다.
        // 5. 커밋된 알림·수신자로 PENDING 작업을 생성하고 발송하는 기존 푸시 흐름을 호출한다.
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
