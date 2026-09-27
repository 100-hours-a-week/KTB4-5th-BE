package com.dameokja.backend.push.application;

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

    public void fail(Long pushNotificationId, LocalDateTime failedAt) {
        getJob(pushNotificationId).fail(failedAt);
    }

    public void cancel(Long pushNotificationId, PushNotificationErrorCode reason) {
        getJob(pushNotificationId).cancel(reason);
    }

    public void expire(Long pushNotificationId) {
        getJob(pushNotificationId).expire();
    }

    private PushNotification getJob(Long pushNotificationId) {
        return pushNotificationRepository.findById(pushNotificationId).orElseThrow();
    }
}
