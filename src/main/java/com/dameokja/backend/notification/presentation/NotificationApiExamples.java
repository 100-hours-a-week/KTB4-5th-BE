package com.dameokja.backend.notification.presentation;

final class NotificationApiExamples {
    static final String LIST_RESPONSE = """
            {
              "code": "NOTI-200-001",
              "message": "알림 목록 조회 성공",
              "data": {
                "expiredIngredientsNum": 3,
                "notifications": [
                  {
                    "notificationId": "1051",
                    "type": "MEMBER",
                    "title": "새 참여자가 들어왔어요",
                    "body": "민주님이 참여했어요",
                    "readAt": null,
                    "createdAt": "2026-08-27T14:22:10+09:00"
                  }
                ],
                "nextCursor": "eyJjcmVhdGVkX2F0Ijoi...",
                "hasNext": true
              }
            }
            """;

    static final String UNREAD_COUNT_RESPONSE = """
            {
              "code": "NOTI-200-001",
              "message": "미읽음 알림 개수 조회 성공",
              "data": {
                "refrigeratorId": "17",
                "unreadCount": 3
              }
            }
            """;

    static final String POLLING_RESPONSE = """
            {
              "code": "NOTI-200-001",
              "message": "최신 알림 확인 성공",
              "data": {
                "notificationId": "1052"
              }
            }
            """;

    private NotificationApiExamples() {
    }
}
