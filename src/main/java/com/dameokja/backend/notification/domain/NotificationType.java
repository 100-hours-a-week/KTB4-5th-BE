package com.dameokja.backend.notification.domain;

public enum NotificationType {
    EXPIRED,
    EXPIRING,
    MEMBER_JOINED,
    MEMBER_KICKED;

    public String apiType() {
        return switch (this) {
            case EXPIRED -> "EXPIRED";
            case EXPIRING -> "EXPIRING_SOON";
            case MEMBER_JOINED, MEMBER_KICKED -> "MEMBER";
        };
    }
}
