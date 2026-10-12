package com.dameokja.backend.push.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationErrorCode;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// 전송은 응답을 최대 10초 기다리므로 트랜잭션을 잡지 않는다.
@Slf4j
@Service
public class PushDispatchService {
    private final PushNotificationRepository pushNotificationRepository;
    private final PushNotificationUpdater pushNotificationUpdater;
    private final PushSendService pushSendService;
    private final Clock clock;
    private final int concurrency;
    private final MeterRegistry meterRegistry;

    public PushDispatchService(PushNotificationRepository pushNotificationRepository,
            PushNotificationUpdater pushNotificationUpdater, PushSendService pushSendService, Clock clock,
            MeterRegistry meterRegistry,
            @Value("${push.dispatch.concurrency:20}") int concurrency) {
        this.pushNotificationRepository = pushNotificationRepository;
        this.pushNotificationUpdater = pushNotificationUpdater;
        this.pushSendService = pushSendService;
        this.clock = clock;
        this.concurrency = concurrency;
        this.meterRegistry = meterRegistry;
        for (PushSendResult result : PushSendResult.values()) {
            meterRegistry.counter("push.dispatch.send.results", "result", result.name().toLowerCase(Locale.ROOT));
        }
    }

    public Optional<LocalDateTime> dispatchDueJobs() {
        List<PushNotification> dueJobs = pushNotificationRepository.findDueJobs(now());
        // 응답이 느린 구독 하나가 뒤의 발송을 밀어내지 않도록 동시에 보낸다.
        // 가상 스레드는 풀로 재사용하지 않고 작업마다 만들며, 동시 전송 수는 세마포어로 제한한다.
        Semaphore permits = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PushNotification job : dueJobs) {
                executor.submit(() -> dispatchWithPermit(job, permits));
            }
        }
        return pushNotificationRepository.findNextAttemptAt();
    }

    private void dispatchWithPermit(PushNotification job, Semaphore permits) {
        try {
            permits.acquire();
        } catch (InterruptedException exception) {
            // 상태를 바꾸지 않았으므로 다음 발송 차례에 다시 처리된다.
            Thread.currentThread().interrupt();
            return;
        }
        try {
            dispatch(job);
        } finally {
            permits.release();
        }
    }

    private void dispatch(PushNotification job) {
        try {
            sendAndRecord(job);
        } catch (RuntimeException exception) {
            log.error("푸시 발송 작업을 처리하지 못했습니다. pushNotificationId={}", job.getId(), exception);
        }
    }

    private void sendAndRecord(PushNotification job) {
        Long jobId = job.getId();
        LocalDateTime now = now();
        if (job.isExpired(now)) {
            pushNotificationUpdater.expire(jobId);
            return;
        }
        if (!job.getUserDevice().getSubscriptionVersion().equals(job.getSubscriptionVersion())) {
            pushNotificationUpdater.cancel(jobId, PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE);
            return;
        }
        if (!pushNotificationRepository.canReceiveExpirationPush(job.getUser().getId(), job.getRefrigerator().getId())) {
            pushNotificationUpdater.cancel(jobId, PushNotificationErrorCode.RECIPIENT_UNAVAILABLE);
            return;
        }
        PushSendResult result = send(job, now);
        recordSendResult(jobId, result);
        switch (result) {
            case ACCEPTED -> pushNotificationUpdater.accept(jobId, now());
            case UNAVAILABLE -> pushNotificationUpdater.cancel(jobId, PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE);
            case FAILED -> pushNotificationUpdater.fail(jobId, now());
        }
    }

    private void recordSendResult(Long jobId, PushSendResult result) {
        // DB 상태 반영이 실패해도 이미 수락된 외부 전송 결과를 계측에서 잃지 않는다.
        meterRegistry.counter("push.dispatch.send.results", "result", result.name().toLowerCase(Locale.ROOT)).increment();
        log.info("event=push_dispatch_send_result push_notification_id={} result={} completed_at={}", jobId, result, now());
    }

    private PushSendResult send(PushNotification job, LocalDateTime now) {
        // 기한이 지난 뒤에는 푸시 서비스도 보관하지 않도록 남은 기한을 TTL로 넘긴다.
        Duration ttl = Duration.between(now, job.getExpiresAt());
        try {
            return pushSendService.send(job.getUserDevice().getId(), job.getPayload(), ttl);
        } catch (RuntimeException exception) {
            log.warn("푸시를 전송하지 못했습니다. pushNotificationId={}", job.getId(), exception);
            return PushSendResult.FAILED;
        }
    }

    private LocalDateTime now() {
        return BusinessTime.now(clock);
    }
}
