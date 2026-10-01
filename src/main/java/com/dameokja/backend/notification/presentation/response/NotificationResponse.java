package com.dameokja.backend.notification.presentation.response;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.domain.NotificationRecipient;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public record NotificationResponse(String notificationId, String type, String title, String body,
        OffsetDateTime createdAt, OffsetDateTime readAt) {
    public static NotificationResponse from(NotificationRecipient recipient) {
        var notification = recipient.getNotification();
        return new NotificationResponse(notification.getId().toString(), notification.getType().apiType(),
                notification.getTitle(), notification.getBody(), toSeoulOffset(notification.getCreatedAt()),
                toSeoulOffset(recipient.getReadAt()));
    }

    private static OffsetDateTime toSeoulOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(BusinessTime.ZONE).toOffsetDateTime();
    }
}
