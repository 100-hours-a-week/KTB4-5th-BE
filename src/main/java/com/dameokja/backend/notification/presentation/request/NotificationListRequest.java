package com.dameokja.backend.notification.presentation.request;

import jakarta.validation.constraints.Pattern;

public record NotificationListRequest(
        @Pattern(regexp = "ALL|READ|UNREAD")
        String type,
        String cursor
) {

    public String typeOrDefault() {
        return type == null ? "ALL" : type;
    }
}
