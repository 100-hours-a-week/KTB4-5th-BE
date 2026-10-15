package com.dameokja.backend.push.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.domain.LeaseDuration;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationErrorCode;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatchWorker {
    private final PushLeaseService leaseService;
    private final PushNotificationRepository repository;
    private final PushNotificationUpdater pushNotificationUpdater;
    private final PushSendService pushSendService;
    private final Clock clock;
    private final WorkerSlots slots;
    @Value("${push.dispatch.lease-duration}")
    private final Duration leaseDuration;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public synchronized List<LeasedJob> poll() {
        int freeSlotCount = slots.freeSlotCount();
        List<LeasedJob> leasedJobs = leaseJobs(freeSlotCount);
        slots.occupy(leasedJobs.size());
        leasedJobs.forEach(this::start);
        return leasedJobs;
    }

    public List<LeasedJob> leaseJobs(int maxJobCount) {
        return leaseService.lease(maxJobCount, new LeaseDuration(leaseDuration));
    }

    private void start(LeasedJob job) {
        try {
            executor.execute(() -> run(job));
        } catch (RejectedExecutionException exception) {
            // 작업이 시작되지 못했으므로 미리 늘린 슬롯 수를 여기서 되돌린다.
            slots.release();
            log.error("푸시 작업을 실행기에 등록하지 못했습니다. pushNotificationId={}", job.jobId(), exception);
        }
    }

    private void run(LeasedJob job) {
        try {
            sendAndRecord(job);
        } catch (RuntimeException exception) {
            log.error("푸시 발송 작업을 처리하지 못했습니다. pushNotificationId={}", job.jobId(), exception);
        } finally {
            slots.release();
        }
    }

    private void sendAndRecord(LeasedJob leasedJob) {
        repository.findLeasedPushJob(leasedJob.jobId(), leasedJob.leaseToken(), BusinessTime.now(clock))
                .ifPresent(job -> sendAndRecord(leasedJob, job));
    }

    private void sendAndRecord(LeasedJob leasedJob, PushNotification job) {
        if (!canSend(leasedJob, job)) {
            return;
        }
        LocalDateTime sendAt = BusinessTime.now(clock);
        if (!sendAt.isBefore(job.getLeaseUntil())) {
            return;
        }
        // 자격 조회에 걸린 시간까지 반영해 전송 직전의 남은 발송 기한을 판단한다.
        if (job.isExpired(sendAt)) {
            pushNotificationUpdater.expire(leasedJob, sendAt);
            return;
        }
        PushSendResult result = send(job, sendAt);
        recordResult(leasedJob, job, result);
    }

    private boolean canSend(LeasedJob leasedJob, PushNotification job) {
        LocalDateTime checkedAt = BusinessTime.now(clock);
        if (job.isExpired(checkedAt)) {
            pushNotificationUpdater.expire(leasedJob, checkedAt);
            return false;
        }
        if (!job.matchesSubscription(job.getUserDevice())) {
            pushNotificationUpdater.cancel(leasedJob, PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE, checkedAt);
            return false;
        }
        if (!repository.canReceiveExpirationPush(job.getUser().getId(), job.getRefrigerator().getId())) {
            pushNotificationUpdater.cancel(leasedJob, PushNotificationErrorCode.RECIPIENT_UNAVAILABLE, BusinessTime.now(clock));
            return false;
        }
        return true;
    }

    private PushSendResult send(PushNotification job, LocalDateTime sendAt) {
        Duration ttl = Duration.between(sendAt, job.getExpiresAt());
        try {
            return pushSendService.send(job, ttl);
        } catch (RuntimeException exception) {
            log.warn("푸시를 전송하지 못했습니다. pushNotificationId={}", job.getId(), exception);
            return PushSendResult.FAILED;
        }
    }

    private void recordResult(LeasedJob leasedJob, PushNotification job, PushSendResult result) {
        LocalDateTime completedAt = BusinessTime.now(clock);
        switch (result) {
            case ACCEPTED -> pushNotificationUpdater.accept(leasedJob, completedAt);
            case UNAVAILABLE -> pushNotificationUpdater.cancel(leasedJob,
                    PushNotificationErrorCode.SUBSCRIPTION_UNAVAILABLE, completedAt);
            case FAILED -> pushNotificationUpdater.fail(leasedJob, job, completedAt);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
