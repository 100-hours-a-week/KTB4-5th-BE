package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.PushNotificationErrorCode;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 전송은 트랜잭션 밖에서 하므로, 결과에 따른 상태 변경만 작업마다 짧은 트랜잭션으로 반영한다.
@Service
@Transactional
@RequiredArgsConstructor
public class PushNotificationUpdater {
    private final PushNotificationRepository pushNotificationRepository;

    public void accept(Long pushNotificationId, LocalDateTime acceptedAt) {
        getJob(pushNotificationId).accept(acceptedAt);
    }

    public boolean accept(LeasedJob job, LocalDateTime acceptedAt) {
        return pushNotificationRepository.acceptLeasedPushJob(job.jobId(), job.leaseToken(), acceptedAt) == 1;
    }

    public void fail(Long pushNotificationId, LocalDateTime failedAt) {
        getJob(pushNotificationId).fail(failedAt);
    }

    public boolean fail(LeasedJob leasedJob, PushNotification job, LocalDateTime failedAt) {
        // 조회한 객체는 읽기 스냅샷이다. 재시도 규칙만 계산하고 저장은 토큰 조건부 UPDATE로 한다.
        job.fail(failedAt);
        return pushNotificationRepository.failLeasedPushJob(leasedJob.jobId(), leasedJob.leaseToken(),
                job.getStatus().name(), job.getAttemptCount(), job.getNextAttemptAt(), failedAt) == 1;
    }

    public void cancel(Long pushNotificationId, PushNotificationErrorCode reason) {
        getJob(pushNotificationId).cancel(reason);
    }

    public boolean cancel(LeasedJob job, PushNotificationErrorCode reason, LocalDateTime now) {
        return pushNotificationRepository.cancelLeasedPushJob(job.jobId(), job.leaseToken(), reason.name(), now) == 1;
    }

    public void expire(Long pushNotificationId) {
        getJob(pushNotificationId).expire();
    }

    public boolean expire(LeasedJob job, LocalDateTime now) {
        return pushNotificationRepository.expireLeasedPushJob(job.jobId(), job.leaseToken(), now) == 1;
    }

    private PushNotification getJob(Long pushNotificationId) {
        return pushNotificationRepository.findById(pushNotificationId)
                .orElseThrow(() -> new CustomException(GlobalExceptionCode.INTERNAL_SERVER_ERROR));
    }
}
