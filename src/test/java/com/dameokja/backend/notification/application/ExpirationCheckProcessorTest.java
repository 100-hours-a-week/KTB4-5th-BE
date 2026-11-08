package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.domain.ExpirationCheck;
import com.dameokja.backend.notification.infrastructure.ExpirationCheckRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({ExpirationCheckRepository.class, ExpirationCheckProcessor.class})
class ExpirationCheckProcessorTest extends NotificationGenerationTestSupport {
    @Autowired private ExpirationCheckRepository checks;
    @Autowired private ExpirationCheckProcessor processor;

    @AfterEach
    void clearReservations() {
        jdbc.update("DELETE FROM expiration_checks WHERE refrigerator_id=?", FRIDGE);
    }

    @Test
    void failedPushRollsBackCompletionAndTheSameRequestCanSucceed() {
        ingredient(970001L, FRIDGE, "우유", 0);
        enablePush();
        checks.reserve(TODAY, FRIDGE);
        var message = reservation(TODAY);
        jdbc.execute("CREATE TRIGGER reject_expiration_push BEFORE INSERT ON push_notifications FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='expiration push failed'");

        try {
            assertThatThrownBy(() -> processor.process(message.payload().kafkaKey(), message))
                    .hasStackTraceContaining("expiration push failed");
            assertThat(messages(FRIDGE)).isEmpty();
            assertThat(recipientIds(FRIDGE)).isEmpty();
            assertThat(completed()).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER reject_expiration_push");
        }

        processor.process(message.payload().kafkaKey(), message);
        assertThat(completed()).isTrue();
        assertThat(messages(FRIDGE)).hasSize(1);
        assertThat(pushCount()).isOne();
    }

    @Test
    void lateRequestUsesOriginalDateAndOnlySavesInbox() {
        LocalDate yesterday = TODAY.minusDays(1);
        ingredient(970001L, FRIDGE, "우유", -1);
        enablePush();
        checks.reserve(yesterday, FRIDGE);
        var message = reservation(yesterday);

        processor.process(message.payload().kafkaKey(), message);

        assertThat(messages(FRIDGE)).extracting(Message::type).containsExactly("EXPIRING");
        assertThat(completed()).isTrue();
        assertThat(pushCount()).isZero();
    }

    private EventEnvelope<ExpirationCheck> reservation(LocalDate date) {
        String messageId = jdbc.queryForObject("SELECT message_id FROM expiration_checks WHERE refrigerator_id=? AND business_date=?",
                String.class, FRIDGE, date);
        return new EventEnvelope<>(messageId, new ExpirationCheck(date, FRIDGE));
    }

    private void enablePush() {
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (?,'EXPIRATION',1)", USER);
        jdbc.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,"
                + "vapid_key_version,status,created_at,updated_at,user_id) "
                + "VALUES (?,'p256dh','secret','v1','v1','ACTIVE',NOW(6),NOW(6),?)", "https://push.example.com/check", USER);
    }

    private long pushCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE refrigerator_id=?", Long.class, FRIDGE);
    }

    private boolean completed() {
        return jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM expiration_checks WHERE refrigerator_id=?",
                Boolean.class, FRIDGE);
    }

}
