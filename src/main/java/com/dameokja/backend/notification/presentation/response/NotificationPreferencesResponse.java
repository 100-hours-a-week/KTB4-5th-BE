package com.dameokja.backend.notification.presentation.response;

import com.dameokja.backend.notification.application.NotificationPreferenceView;
import java.util.List;

public record NotificationPreferencesResponse(List<NotificationPreferenceResponse> notificationPreferences) {
    public static NotificationPreferencesResponse from(List<NotificationPreferenceView> views) {
        return new NotificationPreferencesResponse(views.stream()
                .map(NotificationPreferenceResponse::from)
                .toList());
    }
}
