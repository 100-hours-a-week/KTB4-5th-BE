package com.dameokja.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationRecipientTest {
    private final Notification notification = new Notification(NotificationType.EXPIRING, "제목", "본문",
            new Refrigerator("냉장고", "2026-09"));
    private final User user = new User("사용자", "test");

    @Test
    void startsUnreadWithTheGivenNotificationAndUser() {
        NotificationRecipient recipient = new NotificationRecipient(notification, user);

        assertThat(recipient.getNotification()).isSameAs(notification);
        assertThat(recipient.getUser()).isSameAs(user);
        assertThat(recipient.getReadAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"notification", "user"})
    void rejectsMissingRelationship(String field) {
        assertThatThrownBy(() -> new NotificationRecipient(
                field.equals("notification") ? null : notification,
                field.equals("user") ? null : user))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void preservesFirstReadTimeWhenReadAgain() {
        NotificationRecipient recipient = new NotificationRecipient(notification, user);
        LocalDateTime firstReadAt = LocalDateTime.of(2026, 9, 25, 8, 10);

        recipient.markRead(firstReadAt);
        recipient.markRead(firstReadAt.plusHours(1));

        assertThat(recipient.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void rejectsNullReadTimeWithoutChangingUnreadState() {
        NotificationRecipient recipient = new NotificationRecipient(notification, user);

        assertThatThrownBy(() -> recipient.markRead(null)).isInstanceOf(NullPointerException.class);
        assertThat(recipient.getReadAt()).isNull();
    }
}
