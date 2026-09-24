package com.dameokja.backend.notification.presentation.response;

import com.dameokja.backend.notification.application.NotificationListResult;
import java.util.List;

public record NotificationListResponse(long expiredIngredientsNum,
        List<NotificationResponse> notifications, String nextCursor, boolean hasNext) {
    public static NotificationListResponse from(NotificationListResult result) {
        List<NotificationResponse> notifications = result.recipients().stream()
                .map(NotificationResponse::from)
                .toList();
        return new NotificationListResponse(result.expiredIngredientsNum(), notifications, result.nextCursor(),
                result.nextCursor() != null);
    }
}
