package com.dameokja.backend.refrigerator.presentation;

final class RefrigeratorApiExamples {
    static final String MONTHLY_EXPIRATION_RESPONSE = """
            {
              "code": "INGREDIENT-200-001",
              "message": "이번 달 만료된 재고 목록 조회 성공",
              "data": {"expiredIngredientsNum": 30}
            }
            """;
    static final String ACTIVE_LIST_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-001",
              "message": "활성 냉장고 목록 조회 성공",
              "data": [
                {
                  "refrigeratorId": 1,
                  "name": "우리집 냉장고",
                  "capacity": 1500,
                  "expiredCount": 3
                }
              ]
            }
            """;

    private RefrigeratorApiExamples() {
    }
}
