package com.dameokja.backend.push.application;

import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationErrorCode;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// 전송은 응답을 최대 10초 기다리므로 트랜잭션을 잡지 않는다.
@Slf4j
@Service
public class PushDispatchService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final PushNotificationRepository pushNotificationRepository;
    private final PushNotificationUpdater pushNotificationUpdater;
    private final PushSendService pushSendService;
    private final Clock clock;
    private final int concurrency;

    public PushDispatchService(PushNotificationRepository pushNotificationRepository,
            PushNotificationUpdater pushNotificationUpdater, PushSendService pushSendService, Clock clock,
            @Value("${push.dispatch.concurrency:20}") int concurrency) {
        this.pushNotificationRepository = pushNotificationRepository;
        this.pushNotificationUpdater = pushNotificationUpdater;
        this.pushSendService = pushSendService;
        this.clock = clock;
        this.concurrency = concurrency;
    }

    public Optional<LocalDateTime> dispatchDueJobs() {
        List<PushNotification> dueJobs = pushNotificationRepository.findDueJobs(now());
        // 응답이 느린 구독 하나가 뒤의 발송을 밀어내지 않도록 동시에 보내되, 동시 전송 수는 제한한다.
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().factory())) {
            for (PushNotification job : dueJobs) {
                executor.submit(() -> dispatch(job));
            }
        }
        return pushNotificationRepository.findNextAttemptAt();
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
        switch (send(job, now)) {
            case ACCEPTED -> pushNotificationUpdater.accept(jobId, now());
            case UNAVAILABLE -> pushNotificationUpdater.cancel(jobId, PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE);
            case FAILED -> pushNotificationUpdater.fail(jobId, now());
        }
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
        return LocalDateTime.now(clock.withZone(BUSINESS_ZONE));
    }
}
