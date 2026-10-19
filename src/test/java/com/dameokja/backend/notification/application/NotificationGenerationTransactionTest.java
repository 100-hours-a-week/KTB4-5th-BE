package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.push.application.PushNotificationCreationService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationGenerationTransactionTest extends NotificationGenerationTestSupport {
    @Test
    void rollsBackBothTypesAndAlreadyInsertedRecipientsWhenLaterRecipientInsertFails() {
        member(970004L, FRIDGE, true);
        ingredient(970001L, FRIDGE, "임박", 0);
        ingredient(970002L, FRIDGE, "만료", -1, 970004L);
        failExpiredRecipient(970004L);

        assertThatThrownBy(() -> generationService.generate(FRIDGE))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("batch recipient failure");

        assertThat(messages(FRIDGE)).isEmpty();
        assertThat(recipientIds(FRIDGE)).isEmpty();
    }

    @Test
    void commitsOtherFridgesAndStartsPushAfterFailedFridgeHasRolledBack() {
        refrigerator(970002L);
        member(970002L, 970002L, true);
        member(970004L, 970002L, true);
        refrigerator(970003L);
        member(970003L, 970003L, true);
        ingredient(970001L, FRIDGE, "A임박", 0);
        ingredient(970002L, 970002L, "B임박", 0, 970002L);
        ingredient(970003L, 970002L, "B만료", -1, 970004L);
        ingredient(970004L, 970003L, "C만료", -1, 970003L);
        failExpiredRecipient(970004L);
        PushNotificationCreationService pushCreation = mock(PushNotificationCreationService.class);
        when(pushCreation.createExpirationJobs()).thenAnswer(invocation -> {
            assertCommittedResults();
            return 0;
        });
        NotificationScheduler scheduler = new NotificationScheduler(pushCreation,
                refrigeratorService, generationService, mock(NotificationRecipientService.class), mock(NotificationService.class), clock, true);

        scheduler.runExpirationNotificationBatch();

        assertCommittedResults();
        InOrder order = inOrder(pushCreation);
        order.verify(pushCreation).createExpirationJobs();
    }

    @Test
    void recipientSavingRequiresAnExistingTransaction() {
        Notification notification = new Notification(NotificationType.EXPIRING, "제목", "본문",
                new Refrigerator("냉장고", "2026-09"));
        User user = new User("사용자", "test");

        assertThatThrownBy(() -> recipientService.saveRecipients(notification, List.of(user)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void assertCommittedResults() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(messages(FRIDGE)).hasSize(1);
        assertThat(recipientIds(FRIDGE)).containsExactly(USER);
        assertThat(messages(970002L)).isEmpty();
        assertThat(recipientIds(970002L)).isEmpty();
        assertThat(messages(970003L)).hasSize(1);
        assertThat(recipientIds(970003L)).containsExactly(970003L);
    }

    private void failExpiredRecipient(long userId) {
        // 앞선 등록자의 알림 저장 뒤 다른 등록자의 만료 알림 수신자에서 실패시킨다.
        jdbc.execute("CREATE TRIGGER fail_batch_recipient BEFORE INSERT ON notification_recipients "
                + "FOR EACH ROW BEGIN IF NEW.user_id=" + userId
                + " AND (SELECT type FROM notifications WHERE notification_id=NEW.notification_id)='EXPIRED' "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='batch recipient failure'; END IF; END");
    }
}
