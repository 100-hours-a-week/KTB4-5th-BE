package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

class NotificationGenerationTransactionTest extends NotificationGenerationTestSupport {
    @Test
    void rollsBackBothTypesAndAlreadyInsertedRecipientsWhenLaterRecipientInsertFails() {
        member(970004L, FRIDGE, true);
        ingredient(970001L, FRIDGE, "임박", 0);
        ingredient(970002L, FRIDGE, "만료", -1, 970004L);
        failExpiredRecipient(970004L);

        assertThatThrownBy(() -> generationService.generate(FRIDGE, true))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("batch recipient failure");

        assertThat(messages(FRIDGE)).isEmpty();
        assertThat(recipientIds(FRIDGE)).isEmpty();
    }

    @Test
    void rollsBackInboxAndRecipientsWhenPushInsertFailsAndContinuesWithOtherFridges() {
        enablePushFor(USER);
        ingredient(970001L, FRIDGE, "실패할 알림", 0);
        refrigerator(970002L);
        member(970002L, 970002L, true);
        enablePushFor(970002L);
        ingredient(970002L, 970002L, "정상 알림", 0, 970002L);
        refrigerator(970003L);
        member(970003L, 970003L, true);
        enablePushFor(970003L);
        jdbc.update("UPDATE notification_preferences SET is_enabled=0 WHERE user_id=?", 970003L);
        ingredient(970003L, 970003L, "알림함 전용", -1, 970003L);
        failPushInsertFor(USER);
        NotificationScheduler scheduler = new NotificationScheduler(
                refrigeratorService, generationService, recipientService, mock(NotificationService.class), clock, true);

        try {
            scheduler.runExpirationNotificationBatch();
        } finally {
            jdbc.execute("DROP TRIGGER fail_batch_push");
        }

        assertThat(messages(FRIDGE)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipients WHERE user_id=?", Long.class, USER)).isZero();
        assertThat(messages(970002L)).hasSize(1);
        assertThat(recipientIds(970002L)).containsExactly(970002L);
        assertThat(messages(970003L)).hasSize(1);
        assertThat(recipientIds(970003L)).containsExactly(970003L);
        assertThat(jdbc.queryForList("SELECT user_id FROM push_notifications WHERE user_id BETWEEN 970001 AND 970099",
                Long.class)).containsExactly(970002L);
    }

    @Test
    void recipientSavingRequiresAnExistingTransaction() {
        Notification notification = new Notification(NotificationType.EXPIRING, "제목", "본문",
                new Refrigerator("냉장고", "2026-09"));
        User user = new User("사용자", "test");

        assertThatThrownBy(() -> recipientService.saveRecipients(notification, List.of(user)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void enablePushFor(long userId) {
        jdbc.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (?,'EXPIRATION',1)", userId);
        jdbc.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,"
                + "vapid_key_version,status,created_at,updated_at,user_id) "
                + "VALUES (?,'p256dh','secret','v1','v1','ACTIVE',NOW(6),NOW(6),?)",
                "https://push.example.com/expiration/" + userId, userId);
    }

    private void failPushInsertFor(long userId) {
        jdbc.execute("CREATE TRIGGER fail_batch_push BEFORE INSERT ON push_notifications "
                + "FOR EACH ROW BEGIN IF NEW.user_id=" + userId
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='batch push failure'; END IF; END");
    }

    private void failExpiredRecipient(long userId) {
        // 앞선 등록자의 알림 저장 뒤 다른 등록자의 만료 알림 수신자에서 실패시킨다.
        jdbc.execute("CREATE TRIGGER fail_batch_recipient BEFORE INSERT ON notification_recipients "
                + "FOR EACH ROW BEGIN IF NEW.user_id=" + userId
                + " AND (SELECT type FROM notifications WHERE notification_id=NEW.notification_id)='EXPIRED' "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='batch recipient failure'; END IF; END");
    }
}
