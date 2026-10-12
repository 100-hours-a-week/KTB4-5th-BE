package com.dameokja.backend.push.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.infrastructure.PushInboxTarget;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// 8시 인앱 알림 생성 결과를 읽어야 하므로, 알림·수신자 저장이 커밋된 뒤 호출한다.
@Service
public class PushNotificationCreationService {
    // 8시 알림이 12시를 넘겨 도착하면 이미 늦은 정보이므로 서울 기준 당일 정오까지만 보낸다.
    public static final LocalTime SEND_DEADLINE = LocalTime.NOON;

    private final PushNotificationRepository pushNotificationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate chunkTransaction;
    private final int chunkSize;

    public PushNotificationCreationService(PushNotificationRepository pushNotificationRepository,
            ObjectMapper objectMapper, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${push.expiration-batch.creation-chunk-size:500}") int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("push.expiration-batch.creation-chunk-size must be positive");
        }
        this.pushNotificationRepository = pushNotificationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.chunkSize = chunkSize;
        this.chunkTransaction = new TransactionTemplate(transactionManager);
        this.chunkTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 전체 반복을 하나의 트랜잭션에 묶지 않아 각 묶음 커밋 후 Connection과 영속성 컨텍스트를 반환한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int createExpirationJobs() {
        LocalDateTime now = BusinessTime.now(clock);
        CreatedChunk cursor = new CreatedChunk(0, 0L, 0L);
        int createdCount = 0;
        do {
            CreatedChunk previous = cursor;
            cursor = chunkTransaction.execute(status -> createChunk(now, previous));
            createdCount += cursor.count();
        } while (cursor.count() == chunkSize);
        return createdCount;
    }

    private CreatedChunk createChunk(LocalDateTime now, CreatedChunk cursor) {
        LocalDate today = now.toLocalDate();
        List<PushInboxTarget> targets = pushNotificationRepository.findInboxPushTargets(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                cursor.notificationId(), cursor.deviceId(), PageRequest.of(0, chunkSize));
        if (targets.isEmpty()) {
            return new CreatedChunk(0, cursor.notificationId(), cursor.deviceId());
        }
        saveJobs(targets, now, today.atTime(SEND_DEADLINE));
        PushInboxTarget last = targets.getLast();
        return new CreatedChunk(targets.size(), last.notification().getId(), last.device().getId());
    }

    private void saveJobs(List<PushInboxTarget> targets, LocalDateTime now, LocalDateTime expiresAt) {
        for (PushInboxTarget target : targets) {
            String payload = objectMapper.writeValueAsString(PushPayload.from(target.notification()));
            pushNotificationRepository.save(PushNotification.inbox(
                    target.notification(), target.device().getUser(), target.device(), payload, now, expiresAt));
        }
    }

    private record CreatedChunk(int count, long notificationId, long deviceId) {}
}
