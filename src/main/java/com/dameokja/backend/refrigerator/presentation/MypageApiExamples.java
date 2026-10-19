package com.dameokja.backend.refrigerator.presentation;

final class MypageApiExamples {
    static final String OVERVIEW = """
            {
              "code": "REFRIGERATOR-200-007",
              "message": "마이페이지 조회 성공",
              "data": {
                "userId": "1", "nickname": "dave", "email": "dave@example.com",
                "profileImageUrl": "https://example.com/profile.png?signature=example",
                "refrigeratorId": "17", "refrigeratorName": "우리집 냉장고",
                "ingredientsNum": 62, "expiredIngredientsNum": 5, "memberNum": 2,
                "notificationPreferences": [
                  {"type": "EXPIRATION", "isEnabled": true},
                  {"type": "RECIPE", "isEnabled": false},
                  {"type": "ANALYSIS", "isEnabled": true}
                ]
              }
            }
            """;

    private MypageApiExamples() {}
}
