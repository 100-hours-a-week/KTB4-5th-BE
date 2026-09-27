package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.domain.NotificationRecipient;
import java.util.List;

public record NotificationListResult(List<NotificationRecipient> recipients, String nextCursor,
        long expiredIngredientsNum) {
}
