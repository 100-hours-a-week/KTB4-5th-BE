package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.domain.NotificationPreference;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;

public record NotificationPreferenceView(NotificationPreferenceType type, boolean isEnabled) {
    public static NotificationPreferenceView from(NotificationPreference preference) {
        return new NotificationPreferenceView(preference.getType(), preference.getIsEnabled());
    }
}
