package com.dameokja.backend.notification.presentation.response;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationRecipient;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationResponseTest {
    @Test
    void expressesStoredSeoulTimesWithSeoulOffset() {
        Notification notification = new Notification(NotificationType.EXPIRING, "제목", "본문",
                new Refrigerator("냉장고", "2026-09"));
        ReflectionTestUtils.setField(notification, "id", 1L);
        ReflectionTestUtils.setField(notification, "createdAt", LocalDateTime.of(2026, 9, 30, 13, 33, 34));
        NotificationRecipient recipient = new NotificationRecipient(notification, new User("사용자", "test"));
        recipient.markRead(LocalDateTime.of(2026, 9, 30, 13, 33, 40));

        NotificationResponse response = NotificationResponse.from(recipient);

        assertThat(response.createdAt())
                .isEqualTo(OffsetDateTime.of(2026, 9, 30, 13, 33, 34, 0, ZoneOffset.ofHours(9)));
        assertThat(response.readAt())
                .isEqualTo(OffsetDateTime.of(2026, 9, 30, 13, 33, 40, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    void keepsReadAtEmptyForUnreadNotification() {
        Notification notification = new Notification(NotificationType.EXPIRING, "제목", "본문",
                new Refrigerator("냉장고", "2026-09"));
        ReflectionTestUtils.setField(notification, "id", 1L);
        ReflectionTestUtils.setField(notification, "createdAt", LocalDateTime.of(2026, 9, 30, 13, 33, 34));

        NotificationResponse response = NotificationResponse.from(
                new NotificationRecipient(notification, new User("사용자", "test")));

        assertThat(response.readAt()).isNull();
    }
}
