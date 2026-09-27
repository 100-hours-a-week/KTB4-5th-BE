package com.dameokja.backend.refrigerator.presentation;

final class RefrigeratorApiExamples {
    static final String ACTIVE_LIST_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-001",
              "message": "활성 냉장고 목록 조회 성공",
              "data": [
                {
                  "refrigeratorId": 1,
                  "name": "우리집 냉장고",
                  "capacity": 100,
                  "expiredCount": 3
                }
              ]
            }
            """;

    private RefrigeratorApiExamples() {
    }
}
