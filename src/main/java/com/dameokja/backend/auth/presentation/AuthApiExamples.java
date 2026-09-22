package com.dameokja.backend.auth.presentation;

final class AuthApiExamples {
    static final String LOGIN_REQUEST = """
            {
              "loginId": "dameokja01",
              "password": "abc12345"
            }
            """;

    static final String LOGIN_RESPONSE = """
            {
              "code": "AUTH-200-001",
              "message": "로그인 성공",
              "data": {
                "activeRefrigeratorIds": ["1", "2"]
              }
            }
            """;

    static final String REFRESH_RESPONSE = """
            {
              "code": "AUTH-200-006",
              "message": "인증정보 갱신 성공",
              "data": {
                "userId": "1"
              }
            }
            """;

    static final String LOGOUT_RESPONSE = """
            {
              "code": "AUTH-200-005",
              "message": "로그아웃 성공",
              "data": null
            }
            """;

    private AuthApiExamples() {
    }
}
