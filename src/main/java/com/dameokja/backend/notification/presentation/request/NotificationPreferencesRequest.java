package com.dameokja.backend.notification.presentation.request;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record NotificationPreferencesRequest(@NotEmpty List<@Valid @NotNull Item> notificationPreferences) {
    public Map<NotificationPreferenceType, Boolean> changes() {
        Map<NotificationPreferenceType, Boolean> changes = new EnumMap<>(NotificationPreferenceType.class);
        for (Item item : notificationPreferences) {
            if (changes.put(item.type(), item.isEnabled()) != null) {
                throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
            }
        }
        return changes;
    }

    public record Item(@NotNull NotificationPreferenceType type,
            @NotNull @Schema(example = "false", requiredMode = Schema.RequiredMode.REQUIRED) Boolean isEnabled) {
    }
}
