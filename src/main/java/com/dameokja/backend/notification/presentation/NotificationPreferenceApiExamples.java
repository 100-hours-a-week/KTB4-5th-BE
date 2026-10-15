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
                  },
                  {
                    "type": "ANALYSIS",
                    "isEnabled": true
                  }
                ]
              }
            }
            """;

    static final String CHANGE_REQUEST = """
            {
              "notificationPreferences": [
                {
                  "type": "ANALYSIS",
                  "isEnabled": false
                }
              ]
            }
            """;

    static final String CHANGE_RESPONSE = """
            {
              "code": "NOTI-200-002",
              "message": "알림 설정 변경 성공",
              "data": {
                "notificationPreferences": [
                  {
                    "type": "EXPIRATION",
                    "isEnabled": true
                  },
                  {
                    "type": "RECIPE",
                    "isEnabled": false
                  },
                  {
                    "type": "ANALYSIS",
                    "isEnabled": false
                  }
                ]
              }
            }
            """;

    private NotificationPreferenceApiExamples() {
    }
}
