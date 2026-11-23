package com.dameokja.backend.notification.presentation.response;

import com.dameokja.backend.notification.application.preference.NotificationPreferenceView;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;

public record NotificationPreferenceResponse(NotificationPreferenceType type, boolean isEnabled) {
    public static NotificationPreferenceResponse from(NotificationPreferenceView view) {
        return new NotificationPreferenceResponse(view.type(), view.isEnabled());
    }
}
