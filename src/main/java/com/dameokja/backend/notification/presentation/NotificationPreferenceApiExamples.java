package com.dameokja.backend.notification.presentation;

final class NotificationPreferenceApiExamples {
    static final String PREFERENCES_RESPONSE = """
            {
              "code": "NOTI-200-001",
              "message": "알림 설정 조회 성공",
              "data": {
                "notificationPreferences": [
                  {
                    "type": "EXPIRATION",
                    "isEnabled": true
                  },
                  {
                    "type": "RECIPE",
                    "isEnabled": false
                  }
                ]
              }
            }
            """;

    private NotificationPreferenceApiExamples() {
    }
}
