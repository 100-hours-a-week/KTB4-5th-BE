package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.domain.ExpirationCheck;
import com.dameokja.backend.notification.infrastructure.ExpirationCheckRepository;
import com.dameokja.backend.push.application.PushNotificationCreationService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpirationCheckProcessor {
    private final ExpirationCheckRepository checks;
    private final ExpirationNotificationService notifications;
    private final Clock clock;
    private final boolean pushEnabled;

    public ExpirationCheckProcessor(ExpirationCheckRepository checks, ExpirationNotificationService notifications,
                                   Clock clock, @Value("${push.expiration-batch.enabled:true}") boolean pushEnabled) {
        this.checks = checks;
        this.notifications = notifications;
        this.clock = clock;
        this.pushEnabled = pushEnabled;
    }

    @Transactional
    public void process(String key, EventEnvelope<ExpirationCheck> message) {
        ExpirationCheck check = message.payload();
        if (!check.kafkaKey().equals(key)) {
            throw new IllegalArgumentException("Kafka 키가 판정 요청의 냉장고 ID와 일치하지 않습니다.");
        }
        if (checks.lockAndCheckCompleted(message)) {
            return;
        }
        boolean beforeDeadline = BusinessTime.now(clock)
                .isBefore(check.businessDate().atTime(PushNotificationCreationService.SEND_DEADLINE));
        notifications.generate(check.refrigeratorId(), check.businessDate(), pushEnabled && beforeDeadline);
        checks.markCompleted(check);
    }
}
